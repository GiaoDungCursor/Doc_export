package com.company.office.ui;

import com.company.office.model.DocumentEntity;
import com.company.office.model.TemplateEntity;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.stage.FileChooser;

import java.io.File;
import java.util.List;

public class DocumentsController {
    @FXML private TableView<DocumentEntity> docsTable;
    @FXML private TableColumn<DocumentEntity, String> colId;
    @FXML private TableColumn<DocumentEntity, String> colFilename;
    @FXML private TableColumn<DocumentEntity, String> colType;
    @FXML private TableColumn<DocumentEntity, String> colStatus;
    @FXML private TableColumn<DocumentEntity, Double> colConfidence;
    @FXML private TableColumn<DocumentEntity, String> colCreated;

    @FXML private TextArea rawTextArea;
    @FXML private Label selectedDocLabel;
    @FXML private ComboBox<TemplateEntity> templateComboBox;
    @FXML private Button extractButton;
    @FXML private Button exportButton;
    @FXML private Label statusMessageLabel;

    @FXML
    public void initialize() {
        colId.setCellValueFactory(new PropertyValueFactory<>("id"));
        colFilename.setCellValueFactory(new PropertyValueFactory<>("filename"));
        colType.setCellValueFactory(new PropertyValueFactory<>("documentType"));
        colStatus.setCellValueFactory(new PropertyValueFactory<>("status"));
        colConfidence.setCellValueFactory(new PropertyValueFactory<>("confidence"));
        colCreated.setCellValueFactory(new PropertyValueFactory<>("createdAt"));

        docsTable.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) {
                selectedDocLabel.setText("Selected: " + newVal.getFilename() + " (" + newVal.getId() + ")");
                rawTextArea.setText(newVal.getRawText() != null ? newVal.getRawText() : "[No raw text extracted yet]");
                refreshTemplates(newVal);
                extractButton.setDisable(false);
                exportButton.setDisable(false);
            } else {
                selectedDocLabel.setText("No document selected");
                rawTextArea.clear();
                extractButton.setDisable(true);
                exportButton.setDisable(true);
            }
        });

        templateComboBox.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(TemplateEntity item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.getName() + " (" + item.getTemplateType() + ")");
            }
        });
        templateComboBox.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(TemplateEntity item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.getName() + " (" + item.getTemplateType() + ")");
            }
        });

        refreshDocuments();
        refreshTemplates();
    }

    public void refreshDocuments() {
        AppContext ctx = AppContext.getInstance();
        List<DocumentEntity> list = ctx.getDocumentService().listDocuments();
        docsTable.setItems(FXCollections.observableArrayList(list));
    }

    public void refreshTemplates() {
        refreshTemplates(docsTable.getSelectionModel().getSelectedItem());
    }

    private void refreshTemplates(DocumentEntity document) {
        AppContext ctx = AppContext.getInstance();
        List<TemplateEntity> templates = document == null
                ? ctx.getTemplateService().listTemplates()
                : ctx.getTemplateService().listTemplatesForDocument(document.getDocumentType(), document.getRawText());
        templateComboBox.setItems(FXCollections.observableArrayList(templates));
        if (!templates.isEmpty()) {
            templateComboBox.getSelectionModel().select(0);
        }
    }

    @FXML
    public void onImportDocument() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Chọn tài liệu PDF hoặc Ảnh hóa đơn");
        fileChooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Document Files", "*.pdf", "*.png", "*.jpg", "*.jpeg", "*.tiff"),
                new FileChooser.ExtensionFilter("All Files", "*.*")
        );

        File file = fileChooser.showOpenDialog(docsTable.getScene().getWindow());
        if (file != null) {
            try {
                AppContext ctx = AppContext.getInstance();
                DocumentEntity doc = ctx.getDocumentService().importFile(file, "generic");
                statusMessageLabel.setText("Imported successfully: " + doc.getFilename());
                refreshDocuments();
            } catch (Exception e) {
                statusMessageLabel.setText("Import error: " + e.getMessage());
            }
        }
    }

    @FXML
    public void onExtractDocument() {
        DocumentEntity selected = docsTable.getSelectionModel().getSelectedItem();
        if (selected == null) return;

        statusMessageLabel.setText("Extracting document via Python Sidecar...");
        extractButton.setDisable(true);

        AppContext ctx = AppContext.getInstance();
        ctx.getExtractionService().extractDocument(selected.getId())
                .thenAccept(doc -> Platform.runLater(() -> {
                    statusMessageLabel.setText("Extracted successfully! Status: " + doc.getStatus());
                    refreshDocuments();
                    docsTable.getSelectionModel().select(doc);
                    extractButton.setDisable(false);
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> {
                        statusMessageLabel.setText("Extraction error: " + ex.getMessage());
                        extractButton.setDisable(false);
                        refreshDocuments();
                    });
                    return null;
                });
    }

    @FXML
    public void onExportDocument() {
        DocumentEntity selected = docsTable.getSelectionModel().getSelectedItem();
        TemplateEntity template = templateComboBox.getSelectionModel().getSelectedItem();
        if (selected == null || template == null) {
            statusMessageLabel.setText("Please select a document and a template");
            return;
        }

        statusMessageLabel.setText("Exporting to " + template.getTemplateType() + "...");
        exportButton.setDisable(true);

        AppContext ctx = AppContext.getInstance();
        ctx.getExportService().exportDocument(selected.getId(), template.getId())
                .thenAccept(outputPath -> Platform.runLater(() -> {
                    statusMessageLabel.setText("Exported successfully to: " + outputPath);
                    exportButton.setDisable(false);
                    refreshDocuments();
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> {
                        statusMessageLabel.setText("Export error: " + ex.getMessage());
                        exportButton.setDisable(false);
                    });
                    return null;
                });
    }

    @FXML
    public void onDeleteDocument() {
        DocumentEntity selected = docsTable.getSelectionModel().getSelectedItem();
        if (selected != null) {
            AppContext.getInstance().getDocumentService().deleteDocument(selected.getId());
            refreshDocuments();
            statusMessageLabel.setText("Deleted document: " + selected.getId());
        }
    }
}
