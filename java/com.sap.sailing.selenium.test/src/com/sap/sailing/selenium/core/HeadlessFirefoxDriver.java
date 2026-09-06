package com.sap.sailing.selenium.core;

import java.util.Objects;

import org.openqa.selenium.Capabilities;
import org.openqa.selenium.firefox.FirefoxBinary;
import org.openqa.selenium.firefox.FirefoxDriver;
import org.openqa.selenium.firefox.FirefoxOptions;

/**
 * Specific {@link FirefoxDriver} that is configured to start Firefox in headless mode. In theory, you do not need a
 * specific subclass of {@link FirefoxDriver} but we currently can't apply specific command line options using XML based
 * configuration using {@link TestEnvironmentConfiguration}. So this is currently just a simple workaround but not a
 * long-term solution.
 */
public class HeadlessFirefoxDriver extends FirefoxDriver {

    public HeadlessFirefoxDriver(Capabilities desiredCapabilities) {
        super(constructFirefoxOptions(desiredCapabilities));
        
    }
    
    private static FirefoxOptions constructFirefoxOptions(Capabilities desiredCapabilities) {
        final FirefoxOptions firefoxOptions = new FirefoxOptions(Objects.requireNonNull(desiredCapabilities, "No capabilities seen"));
        final FirefoxBinary firefoxBinary = new FirefoxBinary();
        // Window size is currently being ignored in headless mode
        // Documentation says it should work: https://developer.mozilla.org/en-US/Firefox/Headless_mode
        firefoxBinary.addCommandLineOptions("-headless", "--width=1920", "--height=1080");
        firefoxOptions.setBinary(firefoxBinary);
        // On GPU-less CI runners Firefox may blocklist/disable WebGL, which MapLibre GL needs to render the race map.
        // Force WebGL on via the software (llvmpipe) backend so the map tests get a WebGL context.
        firefoxOptions.addPreference("webgl.force-enabled", /* value */ true);
        firefoxOptions.addPreference("webgl.disabled", /* value */ false);
        firefoxOptions.addPreference("webgl.disable-fail-if-major-performance-caveat", /* value */ true);
        firefoxOptions.addPreference("gfx.webrender.software", /* value */ true);
        // Firefox on Linux obtains its GL context through a display server; on the CI runner the tests run under a
        // virtual X display (xvfb) with the MOZ_X11_EGL environment variable set. Force the EGL-over-X11 backend so
        // Firefox binds the Mesa llvmpipe software renderer via EGL rather than legacy GLX, which is what gives
        // MapLibre a working WebGL context there.
        firefoxOptions.addPreference("gfx.x11-egl.force-enabled", /* value */ true);
        return firefoxOptions;
    }
}
