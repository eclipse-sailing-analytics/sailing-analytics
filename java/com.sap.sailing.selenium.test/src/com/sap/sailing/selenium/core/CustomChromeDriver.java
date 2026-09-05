package com.sap.sailing.selenium.core;

import org.openqa.selenium.Capabilities;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeDriverService;
import org.openqa.selenium.chrome.ChromeOptions;

public class CustomChromeDriver extends ChromeDriver {
    public CustomChromeDriver(Capabilities capabilities) {
        super(ChromeDriverService.createDefaultService(), buildOptions());
    }

    private static ChromeOptions buildOptions() {
        final ChromeOptions options = new ChromeOptions();
        options.addArguments("--no-sandbox");
        options.addArguments("--disable-dev-shm-usage");
        // Recent Chromium (>=139) no longer auto-falls back to SwiftShader software rendering when the GPU is
        // unavailable, so MapLibre GL cannot obtain a WebGL context. Do not pass --disable-gpu and explicitly opt into
        // the (deprecated but still supported) software WebGL backend so the race map renders in local Chrome runs.
        options.addArguments("--enable-unsafe-swiftshader", "--use-gl=angle", "--use-angle=swiftshader");
        options.addArguments("--remote-debugging-port=9222");
        ChromeBinary.configureBinary(options);
        return options;
    }
}