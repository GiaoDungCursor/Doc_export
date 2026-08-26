package com.company.office.ui;

import com.company.office.model.JobEntity;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.cell.PropertyValueFactory;

import java.util.List;

public class JobsController {
    @FXML private TableView<JobEntity> jobsTable;
    @FXML private TableColumn<JobEntity, String> colJobId;
    @FXML private TableColumn<JobEntity, String> colDocId;
    @FXML private TableColumn<JobEntity, String> colType;
    @FXML private TableColumn<JobEntity, String> colStatus;
    @FXML private TableColumn<JobEntity, Double> colProgress;
    @FXML private TableColumn<JobEntity, String> colCreated;

    @FXML private TextArea jobResultArea;

    @FXML
    public void initialize() {
        colJobId.setCellValueFactory(new PropertyValueFactory<>("id"));
        colDocId.setCellValueFactory(new PropertyValueFactory<>("documentId"));
        colType.setCellValueFactory(new PropertyValueFactory<>("jobType"));
        colStatus.setCellValueFactory(new PropertyValueFactory<>("status"));
        colProgress.setCellValueFactory(new PropertyValueFactory<>("progress"));
        colCreated.setCellValueFactory(new PropertyValueFactory<>("createdAt"));

        jobsTable.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) {
                String result = newVal.getResultData() != null ? newVal.getResultData() : (newVal.getErrorMessage() != null ? "ERROR: " + newVal.getErrorMessage() : "In progress...");
                jobResultArea.setText(result);
            } else {
                jobResultArea.clear();
            }
        });

        refreshJobs();
    }

    @FXML
    public void refreshJobs() {
        AppContext ctx = AppContext.getInstance();
        List<JobEntity> list = ctx.getJobService().getAllJobs();
        jobsTable.setItems(FXCollections.observableArrayList(list));
    }
}
