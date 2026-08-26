package com.company.office.ui;

import com.company.office.model.DocumentEntity;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.cell.PropertyValueFactory;

import java.util.List;

public class DashboardController {
    @FXML private Label totalDocsLabel;
    @FXML private Label validatedDocsLabel;
    @FXML private Label reviewDocsLabel;
    @FXML private Label templatesCountLabel;

    @FXML private TableView<DocumentEntity> recentDocsTable;
    @FXML private TableColumn<DocumentEntity, String> colId;
    @FXML private TableColumn<DocumentEntity, String> colFilename;
    @FXML private TableColumn<DocumentEntity, String> colType;
    @FXML private TableColumn<DocumentEntity, String> colStatus;
    @FXML private TableColumn<DocumentEntity, Double> colConfidence;

    @FXML
    public void initialize() {
        colId.setCellValueFactory(new PropertyValueFactory<>("id"));
        colFilename.setCellValueFactory(new PropertyValueFactory<>("filename"));
        colType.setCellValueFactory(new PropertyValueFactory<>("documentType"));
        colStatus.setCellValueFactory(new PropertyValueFactory<>("status"));
        colConfidence.setCellValueFactory(new PropertyValueFactory<>("confidence"));

        refreshData();
    }

    public void refreshData() {
        AppContext ctx = AppContext.getInstance();
        List<DocumentEntity> docs = ctx.getDocumentService().listDocuments();

        long total = docs.size();
        long validated = docs.stream().filter(d -> "VALIDATED".equals(d.getStatus()) || "EXPORTED".equals(d.getStatus())).count();
        long review = docs.stream().filter(d -> "NEEDS_REVIEW".equals(d.getStatus())).count();
        long templates = ctx.getTemplateService().listTemplates().size();

        totalDocsLabel.setText(String.valueOf(total));
        validatedDocsLabel.setText(String.valueOf(validated));
        reviewDocsLabel.setText(String.valueOf(review));
        templatesCountLabel.setText(String.valueOf(templates));

        recentDocsTable.setItems(FXCollections.observableArrayList(docs));
    }
}
