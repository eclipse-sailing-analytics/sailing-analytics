package com.sap.sailing.selenium.core;

import org.openqa.selenium.Capabilities;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeDriverService;
import org.openqa.selenium.chrome.ChromeOptions;

/**
 * Specific {@link ChromeDriver} that is configured to start Chrome without GPU support and without extensions.
 * This is helpful when running, e.g., in a Docker environment where full-fledged UI support is not given.
 */
public class DockerChromeDriver extends ChromeDriver {

    public DockerChromeDriver(Capabilities capabilities) {
        super(ChromeDriverService.createDefaultService(), constructChromeOptions(capabilities));
    }

    private static ChromeOptions constructChromeOptions(Capabilities capabilities) {
        final ChromeOptions chromeOptions = new ChromeOptions();
        chromeOptions.merge(capabilities);
        chromeOptions.addArguments("--disable-extensions", "--window-size=1440,900");
        // Recent Chromium (>=139) no longer auto-falls back to SwiftShader software rendering when the GPU is
        // unavailable, so MapLibre GL cannot obtain a WebGL context. Do not pass --disable-gpu and explicitly opt into
        // the (deprecated but still supported) software WebGL backend so the race map renders in this Docker setup.
        chromeOptions.addArguments("--enable-unsafe-swiftshader", "--use-gl=angle", "--use-angle=swiftshader");
        chromeOptions.addArguments("--no-sandbox");
        chromeOptions.addArguments("--disable-dev-shm-usage");
        chromeOptions.addArguments("--remote-debugging-address=0.0.0.0");
        chromeOptions.addArguments("--remote-debugging-port=9222");
        chromeOptions.setExperimentalOption("useAutomationExtension", /* value */ false);
        ChromeBinary.configureBinary(chromeOptions);
        return chromeOptions;
    }
}
