package com.sap.sailing.selenium.pages.leaderboard;

import java.util.Arrays;
import java.util.logging.Logger;

import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

import com.sap.sailing.selenium.pages.common.DataEntryDialogPO;
import com.sap.sailing.selenium.pages.gwt.CheckBoxPO;
import com.sap.sailing.selenium.pages.raceboard.RaceBoardPage;

public class LeaderboardSettingsDialogPO extends DataEntryDialogPO {
    private static final Logger logger = Logger.getLogger(LeaderboardSettingsDialogPO.class.getName());

    private LeaderboardSettingsPanelPO leaderboardSettingsPanelPO;

    public LeaderboardSettingsDialogPO(WebDriver driver, WebElement element) {
        super(driver, element);
        leaderboardSettingsPanelPO = new LeaderboardSettingsPanelPO(this.driver, this.getWebElement());
    }

    public LeaderboardSettingsPanelPO getLeaderboardSettingsPanelPO() {
        return leaderboardSettingsPanelPO;
    }

    public LeaderboardSettingsDialogPO waitForRaceDetailsAverageSpeedUntil(RaceBoardPage raceboard, boolean expected,
            int attempts) {
        LeaderboardSettingsDialogPO leaderboardSettings;
        try {
            WebElement element = findElementBySeleniumId("RaceAverageSpeedOverGroundInKnotsCheckBox");
            new CheckBoxPO(driver, element).waitForElementUntil(expected);
            leaderboardSettings = this;
        } catch (TimeoutException timeoutException) {
            logger.warning(
                    "timeout waiting for wind-up check box being ticked; trying again " + attempts + " more times...");
            if (attempts > 1) {
                pressCancel();
                leaderboardSettings = raceboard.openLeaderboardSettingsDialog().waitForRaceDetailsAverageSpeedUntil(raceboard, expected, attempts - 1);
            } else {
                leaderboardSettings = null;
            }
        }
        return leaderboardSettings;
    }
    
    public LeaderboardSettingsDialogPO waitForExpectedSettings(RaceBoardPage raceboard, DetailCheckboxInfo[] expected, int attempts) throws InterruptedException {
        DetailCheckboxInfo[] selectedDetails;
        LeaderboardSettingsDialogPO leaderboardSettingsDialog = this;
        LeaderboardSettingsPanelPO leaderboardSettingsPanelPO;
        boolean foundExpectedSettings;
        do {
            leaderboardSettingsPanelPO = leaderboardSettingsDialog.getLeaderboardSettingsPanelPO();
            selectedDetails = leaderboardSettingsPanelPO.getSelectedDetails();
            foundExpectedSettings = Arrays.equals(expected, selectedDetails);
            if (!foundExpectedSettings) {
                logger.warning(
                        "didn't find settings "+Arrays.toString(expected)+
                        " but "+Arrays.toString(selectedDetails)+
                        " trying " + (attempts-1) + " more times...");
                leaderboardSettingsDialog.pressCancel();
                Thread.sleep(1000);
                leaderboardSettingsDialog = raceboard.openLeaderboardSettingsDialog();
            }
        } while (!foundExpectedSettings && --attempts > 0);
        return leaderboardSettingsDialog;
    }
}
