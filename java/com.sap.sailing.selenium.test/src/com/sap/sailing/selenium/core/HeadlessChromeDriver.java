package com.sap.sailing.selenium.core;

import org.openqa.selenium.Capabilities;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeDriverService;
import org.openqa.selenium.chrome.ChromeOptions;

/**
 *Specific {@link ChromeDriver} that is configured to start Chrome in headless mode. In theory, you do not need a
 * specific subclass of {@link ChromeDriver} but we currently can't apply specific command line options using XML based
 * configuration using {@link TestEnvironmentConfiguration}. So this is currently just a simple workaround but not a
 * long-term solution.
 */
public class HeadlessChromeDriver extends ChromeDriver {

    public HeadlessChromeDriver(Capabilities capabilities) {
        super(ChromeDriverService.createDefaultService(), constructChromeOptions(capabilities));
    }

    private static ChromeOptions constructChromeOptions(Capabilities capabilities) {
        final ChromeOptions chromeOptions = new ChromeOptions();
        chromeOptions.merge(capabilities);
        chromeOptions.addArguments("--headless=new", "--disable-extensions", "--window-size=1440,900");
        // Recent Chromium (>=139) no longer auto-falls back to SwiftShader software rendering when the GPU is
        // unavailable, so MapLibre GL cannot obtain a WebGL context. Do not pass --disable-gpu and explicitly opt into
        // the (deprecated but still supported) software WebGL backend so the race map renders under headless Chrome.
        chromeOptions.addArguments("--enable-unsafe-swiftshader", "--use-gl=angle", "--use-angle=swiftshader");
        chromeOptions.setExperimentalOption("useAutomationExtension", /* value */ false);
        ChromeBinary.configureBinary(chromeOptions);
        return chromeOptions;
    }
}
