package com.company.office.ui;

import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.TabPane;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GuideController {
    private static final Logger logger = LoggerFactory.getLogger(GuideController.class);

    @FXML private TabPane guideTabPane;
    @FXML private Label copyToastLabel;

    @FXML
    public void initialize() {
        if (copyToastLabel != null) {
            copyToastLabel.setVisible(false);
        }
    }

    public void copyPlaceholder(String tag) {
        Clipboard clipboard = Clipboard.getSystemClipboard();
        ClipboardContent content = new ClipboardContent();
        content.putString(tag);
        clipboard.setContent(content);

        if (copyToastLabel != null) {
            copyToastLabel.setText("Đã sao chép: " + tag);
            copyToastLabel.setVisible(true);
            new Thread(() -> {
                try {
                    Thread.sleep(2500);
                    javafx.application.Platform.runLater(() -> copyToastLabel.setVisible(false));
                } catch (InterruptedException ignored) {}
            }).start();
        }
        logger.info("Copied placeholder tag to clipboard: {}", tag);
    }

    @FXML public void copyTagDocNum() { copyPlaceholder("{{document_number}}"); }
    @FXML public void copyTagIssuing() { copyPlaceholder("{{issuing_authority}}"); }
    @FXML public void copyTagParent() { copyPlaceholder("{{parent_authority}}"); }
    @FXML public void copyTagPlace() { copyPlaceholder("{{place}}"); }
    @FXML public void copyTagDay() { copyPlaceholder("{{day}}"); }
    @FXML public void copyTagMonth() { copyPlaceholder("{{month}}"); }
    @FXML public void copyTagYear() { copyPlaceholder("{{year}}"); }
    @FXML public void copyTagTitle() { copyPlaceholder("{{title}}"); }
    @FXML public void copyTagSummary() { copyPlaceholder("{{summary}}"); }
    @FXML public void copyTagRecipient() { copyPlaceholder("{{recipient}}"); }
    @FXML public void copyTagContent() { copyPlaceholder("{{content}}"); }
    @FXML public void copyTagConclusion() { copyPlaceholder("{{conclusion}}"); }
    @FXML public void copyTagSignerTitle() { copyPlaceholder("{{signer_title}}"); }
    @FXML public void copyTagSignerName() { copyPlaceholder("{{signer_name}}"); }
    @FXML public void copyTagTaxCode() { copyPlaceholder("{{tax_code}}"); }
    @FXML public void copyTagTotalAmount() { copyPlaceholder("{{total_amount}}"); }
}
