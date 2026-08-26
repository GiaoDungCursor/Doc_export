package com.company.office.ui;

import com.company.office.model.DocumentEntity;
import com.company.office.model.DocumentFieldEntity;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.control.cell.TextFieldTableCell;

import java.util.List;

public class ReviewController {
    @FXML private ComboBox<DocumentEntity> docSelectorComboBox;
    @FXML private Label docStatusLabel;
    @FXML private Label docConfidenceLabel;
    @FXML private Label statusMessageLabel;

    @FXML private TableView<DocumentFieldEntity> fieldsTable;
    @FXML private TableColumn<DocumentFieldEntity, String> colFieldName;
    @FXML private TableColumn<DocumentFieldEntity, String> colFieldValue;
    @FXML private TableColumn<DocumentFieldEntity, String> colRawValue;
    @FXML private TableColumn<DocumentFieldEntity, String> colDataType;
    @FXML private TableColumn<DocumentFieldEntity, Double> colConfidence;
    @FXML private TableColumn<DocumentFieldEntity, Boolean> colValidated;
    @FXML private TableColumn<DocumentFieldEntity, String> colError;

    @FXML
    public void initialize() {
        fieldsTable.setEditable(true);

        colFieldName.setCellValueFactory(new PropertyValueFactory<>("fieldName"));
        colFieldValue.setCellValueFactory(new PropertyValueFactory<>("fieldValue"));
        colFieldValue.setCellFactory(TextFieldTableCell.forTableColumn());
        colFieldValue.setOnEditCommit(event -> {
            DocumentFieldEntity field = event.getRowValue();
            field.setFieldValue(event.getNewValue());
            field.setValidated(true);
            field.setValidationError(null);
            statusMessageLabel.setText("Modified field: " + field.getFieldName() + " -> " + event.getNewValue());
        });

        colRawValue.setCellValueFactory(new PropertyValueFactory<>("rawValue"));
        colDataType.setCellValueFactory(new PropertyValueFactory<>("dataType"));
        colConfidence.setCellValueFactory(new PropertyValueFactory<>("confidence"));
        colValidated.setCellValueFactory(new PropertyValueFactory<>("validated"));
        colError.setCellValueFactory(new PropertyValueFactory<>("validationError"));

        docSelectorComboBox.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(DocumentEntity item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.getFilename() + " [" + item.getStatus() + "]");
            }
        });
        docSelectorComboBox.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(DocumentEntity item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.getFilename() + " [" + item.getStatus() + "]");
            }
        });

        docSelectorComboBox.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) {
                loadDocumentFields(newVal);
            }
        });

        refreshDocumentList();
    }

    public void refreshDocumentList() {
        AppContext ctx = AppContext.getInstance();
        List<DocumentEntity> docs = ctx.getDocumentService().listDocuments();
        docSelectorComboBox.setItems(FXCollections.observableArrayList(docs));
        if (!docs.isEmpty()) {
            docSelectorComboBox.getSelectionModel().select(0);
        }
    }

    private void loadDocumentFields(DocumentEntity doc) {
        docStatusLabel.setText("Status: " + doc.getStatus());
        docConfidenceLabel.setText(String.format("Confidence: %.1f%%", doc.getConfidence() * 100));
        fieldsTable.setItems(FXCollections.observableArrayList(doc.getFields()));
    }

    @FXML
    public void onSaveAndValidate() {
        DocumentEntity selected = docSelectorComboBox.getSelectionModel().getSelectedItem();
        if (selected == null) return;

        AppContext ctx = AppContext.getInstance();
        // Update fields in database
        for (DocumentFieldEntity f : fieldsTable.getItems()) {
            ctx.getValidationService().updateFieldManualReview(selected.getId(), f.getFieldName(), f.getFieldValue());
        }

        // Reload document
        ctx.getDocumentService().getDocument(selected.getId()).ifPresent(updated -> {
            statusMessageLabel.setText("Updated & Validated successfully! Status: " + updated.getStatus());
            loadDocumentFields(updated);
        });
    }
}
