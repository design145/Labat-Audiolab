#include <Wire.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#include "esp_sleep.h"  // Include ESP sleep API

// GPIO LED Pins
const int gpio_leds[] = {2, 4, 5, 12, 13, 14, 15, 16, 27, 18};
const int NUM_GPIO_LEDS = sizeof(gpio_leds) / sizeof(gpio_leds[0]);

// I2C Settings
#define I2C_SDA 21
#define I2C_SCL 22
#define I2C_SLAVE_ADDRESS 0x08

// LED Registers (I2C)
#define LED_REG_START 0x03
#define LED_REG_END 0x06
#define LEDS_PER_REG 8

// BLE UUIDs
#define SERVICE_UUID        "4fafc201-1fb5-459e-8fcc-c5c9c331914b"
#define CHARACTERISTIC_UUID "beb5483e-36e1-4688-b7f5-ea07361b26a8"

BLEServer *pServer = nullptr;
BLECharacteristic *pCharacteristic = nullptr;
bool deviceConnected = false;
bool testInProgress = false;

// Key map structure
struct KeyMap {
  const char* name;
  uint8_t bitmask;
  uint8_t index;
  uint8_t reg;
};

// 24 key definitions
const KeyMap keyMap[24] = {
  {"EAR", 0x01, 0, 0}, {"MODE", 0x02, 1, 0}, {"STIMTYPE", 0x04, 2, 0}, {"TALKOVER", 0x08, 3, 0},
  {"PULSE", 0x10, 4, 0}, {"INVERT", 0x20, 5, 0}, {"TALKBACK", 0x40, 6, 0}, {"SAVE", 0x80, 7, 0},
  {"CANCEL", 0x01, 8, 1}, {"TEST", 0x02, 9, 1}, {"20dB", 0x04, 10, 1}, {"NO_RESP", 0x08, 11, 1},
  {"MARK", 0x10, 12, 1}, {"STIMULUS", 0x20, 13, 1}, {"MASKING", 0x40, 14, 1}, {"F1", 0x80, 15, 1},
  {"F2", 0x01, 16, 2}, {"SELECT", 0x02, 17, 2}, {"INT1+", 0x04, 18, 2}, {"INT1-", 0x08, 19, 2},
  {"FREQ+", 0x10, 20, 2}, {"FREQ-", 0x20, 21, 2}, {"INT2+", 0x40, 22, 2}, {"INT2-", 0x80, 23, 2}
};

// Key data buffers
uint8_t key_data[3] = {0};
uint8_t previous_keys[3] = {0};

// Toggle states for each key
bool keyToggleState[24] = {false};

// Global key pressed state
bool key_states[24] = {false};

void startLedTest();
void startKeyTest();
void checkKeys();

class MyServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer* pServer) override {
    deviceConnected = true;
    Serial.println("BLE Device Connected");
    if (pCharacteristic != nullptr) {
        pCharacteristic->setValue("BLE Connected");
        pCharacteristic->notify();
    }

    delay(500); // Delay for BLE and I2C stability

    testInProgress = true;
    startLedTest();
    testInProgress = false;
  }

  void onDisconnect(BLEServer* pServer) override {
    deviceConnected = false;
    Serial.println("BLE Device Disconnected");
    if (pCharacteristic != nullptr) {
        pCharacteristic->setValue("BLE Disconnected");
        pCharacteristic->notify();
    }
    pServer->startAdvertising();
  }
};

class MyCharacteristicCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *pCharacteristic) override {
    std::string value = pCharacteristic->getValue().c_str();

    if (value == "CONNECT_BLE") {
        Serial.println("Received CONNECT_BLE command. Starting advertising...");
        pServer->getAdvertising()->start();
        Serial.println("Waiting for app to trigger BLE advertising...");
    } else if (value == "START_LED_TEST") {
        testInProgress = true;
        startLedTest();
        testInProgress = false;
    } else if (value == "START_KEY_TEST") {
        testInProgress = true;
        startKeyTest();
        testInProgress = false;
    }
  }
};

void setup() {
    Serial.begin(115200);
    Wire.begin(I2C_SDA, I2C_SCL);

    // Prevent ESP32 from entering sleep modes
    esp_sleep_disable_wakeup_source(ESP_SLEEP_WAKEUP_ALL);
    esp_sleep_pd_config(ESP_PD_DOMAIN_RTC_PERIPH, ESP_PD_OPTION_ON);
    setCpuFrequencyMhz(240);  // Optional: lock CPU freq

    for (int i = 0; i < NUM_GPIO_LEDS; i++) {
        pinMode(gpio_leds[i], OUTPUT);
        digitalWrite(gpio_leds[i], LOW);
    }

    BLEDevice::init("ESP32_Device");
    pServer = BLEDevice::createServer();
    pServer->setCallbacks(new MyServerCallbacks());

    BLEService *pService = pServer->createService(SERVICE_UUID);
    pCharacteristic = pService->createCharacteristic(
        CHARACTERISTIC_UUID,
        BLECharacteristic::PROPERTY_READ |
        BLECharacteristic::PROPERTY_WRITE |
        BLECharacteristic::PROPERTY_NOTIFY
    );

    pCharacteristic->addDescriptor(new BLE2902());
    pCharacteristic->setCallbacks(new MyCharacteristicCallbacks());
    pService->start();
    pServer->getAdvertising()->start();

    Serial.println("BLE advertising started.");
}

void loop() {
    if (!testInProgress) {
        checkKeys();
    }
    delay(100);
}

void startLedTest() {
    Serial.println("Starting LED test...");

    for (int i = 0; i < NUM_GPIO_LEDS; i++) {
        digitalWrite(gpio_leds[i], HIGH);

        if (deviceConnected && pCharacteristic != nullptr) {
            String msg = "LED_ON_" + String(i + 1);
            pCharacteristic->setValue(msg.c_str());
            pCharacteristic->notify();
        }

        digitalWrite(gpio_leds[i], LOW);
    }

    for (uint8_t reg = LED_REG_START; reg <= LED_REG_END; reg++) {
        for (uint8_t bit = 0; bit < LEDS_PER_REG; bit++) {
            uint8_t led_num = (reg - LED_REG_START) * LEDS_PER_REG + bit + 1 + NUM_GPIO_LEDS;
            writeI2C(I2C_SLAVE_ADDRESS, reg, 1 << bit);

            Serial.printf("I2C LED %d (Reg 0x%02X, Bit %d) ON\n", led_num, reg, bit);

            if (deviceConnected && pCharacteristic != nullptr) {
                String msg = "LED_ON_" + String(led_num);
                pCharacteristic->setValue(msg.c_str());
                pCharacteristic->notify();
            }

            delay(500);
            writeI2C(I2C_SLAVE_ADDRESS, reg, 0x00);
        }
    }

    Serial.println("LED test complete.");
}

void startKeyTest() {
    Serial.println("Starting key test...");
    for (int i = 0; i < 3; i++) {
        int val = readI2C(I2C_SLAVE_ADDRESS, i);
        key_data[i] = (val != -1) ? val : 0;
    }

    for (int i = 0; i < 24; i++) {
        const KeyMap &key = keyMap[i];
        bool prev = previous_keys[key.reg] & key.bitmask;
        bool curr = key_data[key.reg] & key.bitmask;

        if (curr && !prev) {
            Serial.printf("Key %d (%s) pressed\n", key.index + 1, key.name);
            if (deviceConnected && pCharacteristic != nullptr) {
                String msg = "KEY_ON_" + String(key.index + 1);
                pCharacteristic->setValue(msg.c_str());
                pCharacteristic->notify();
            }
        }
    }

    memcpy(previous_keys, key_data, sizeof(key_data));
    Serial.println("Key test complete.");
}

void checkKeys() {
    for (int i = 0; i < 3; i++) {
        int val = readI2C(I2C_SLAVE_ADDRESS, i);
        key_data[i] = (val != -1) ? val : 0;
    }

    for (int i = 0; i < 24; i++) {
        const KeyMap &key = keyMap[i];
        bool prev = previous_keys[key.reg] & key.bitmask;
        bool curr = key_data[key.reg] & key.bitmask;

        if (curr && !prev) {
            Serial.printf("KEY PRESSED: %s\n", key.name);
            if (deviceConnected && pCharacteristic != nullptr) {
                String msg = "KEY PRESSED: " + String(key.name);
                pCharacteristic->setValue(msg.c_str());
                pCharacteristic->notify();
            }
        }
    }

    memcpy(previous_keys, key_data, sizeof(key_data));
}

void writeI2C(uint8_t deviceAddr, uint8_t reg, uint8_t value) {
    Wire.beginTransmission(deviceAddr);
    Wire.write(reg);
    Wire.write(value);
    Wire.endTransmission();
}

int readI2C(uint8_t deviceAddr, uint8_t reg) {
    Wire.beginTransmission(deviceAddr);
    Wire.write(reg);
    if (Wire.endTransmission(false) != 0) {
        return -1;
    }

    Wire.requestFrom(deviceAddr, (uint8_t)1);
    if (Wire.available()) {
        return Wire.read();
    }
    return -1;
}
// @@@@@@@@@@@@@@@@@@@@@@@