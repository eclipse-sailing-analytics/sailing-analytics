package com.sap.sailing.selenium.core;

import org.openqa.selenium.chrome.ChromeOptions;

/**
 * Helper that lets the Selenium tests run against an explicit, non-snap Chrome/Chromium binary. On Ubuntu 24.04 the
 * distribution's {@code chromium} is a confined snap whose sandbox prevents Chromium's GPU/SwiftShader sub-process from
 * starting ("BindToCurrentSequence failed", {@code GL_VENDOR = Disabled}). As a result MapLibre GL cannot obtain a
 * WebGL context and the race map tests fail. Set the system property {@value #CHROME_BINARY_PROPERTY} (or the
 * environment variable {@value #CHROME_BINARY_ENV}) to the path of a non-snap Chrome/Chromium binary to work around
 * this. When neither is set the Selenium default binary resolution is kept unchanged.
 */
final class ChromeBinary {

    static final String CHROME_BINARY_PROPERTY = "selenium.chrome.binary";
    static final String CHROME_BINARY_ENV = "CHROME_BIN";

    private ChromeBinary() {
    }

    static void configureBinary(final ChromeOptions options) {
        final String fromProperty = System.getProperty(CHROME_BINARY_PROPERTY);
        final String binaryPath = (fromProperty != null && !fromProperty.isEmpty()) ? fromProperty
                : System.getenv(CHROME_BINARY_ENV);
        if (binaryPath != null && !binaryPath.isEmpty()) {
            options.setBinary(binaryPath);
        }
    }
}
