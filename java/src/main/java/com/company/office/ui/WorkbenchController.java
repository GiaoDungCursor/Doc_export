package com.company.office.ui;

import com.company.office.model.DocumentEntity;
import com.company.office.model.DocumentFieldEntity;
import com.company.office.model.TemplateEntity;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.Node;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Desktop;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public class WorkbenchController {
    private static final Logger logger = LoggerFactory.getLogger(WorkbenchController.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @FXML private ComboBox<DocumentEntity> docSelectorComboBox;
    @FXML private Button toggleGuideButton;
    @FXML private VBox quickGuideBox;
    @FXML private Label docStatusBadge;
    @FXML private Label docConfidenceBadge;

    // Viewer Controls
    @FXML private ScrollPane viewerScrollPane;
    @FXML private StackPane viewerStackPane;
    @FXML private ImageView pageImageView;
    @FXML private Canvas bboxCanvas;
    @FXML private Label pageLabel;
    @FXML private Label zoomLabel;
    @FXML private CheckBox showBboxesCheckbox;

    // Right Parsing Tabs
    @FXML private TabPane parsingTabPane;
    @FXML private ScrollPane parsingScrollPane;
    @FXML private VBox fieldsContainer;
    @FXML private VBox tablesContainer;
    @FXML private Label tablesCountLabel;
    @FXML private VBox blocksContainer;
    @FXML private Label blocksCountLabel;
    @FXML private TextArea jsonTextArea;
    @FXML private TextArea rawTextArea;

    // Export Controls
    @FXML private ComboBox<TemplateEntity> templateComboBox;
    @FXML private Button exportButton;
    @FXML private HBox exportResultBox;
    @FXML private Button openFileButton;
    @FXML private Label statusMessageLabel;

    private DocumentEntity currentDocument;
    private List<Map<String, Object>> pagesData = new ArrayList<>();
    private List<Map<String, Object>> tablesData = new ArrayList<>();
    private int currentPageIndex = 0;
    private double zoomFactor = 0.45;
    private double originalImgWidth = 1488;
    private double originalImgHeight = 2105;
    private String highlightedField = null;
    private int selectedBlockIndex = -1;
    private String lastExportedPath = null;

    private double dragStartX;
    private double dragStartY;

    private final Map<String, TextField> fieldInputMap = new HashMap<>();
    private final List<TextArea> blockInputList = new ArrayList<>();
    private final List<VBox> blockCardList = new ArrayList<>();

    @FXML
    public void initialize() {
        setupComboBoxes();
        setupCanvasInteractions();
        setupZoomAndScrollInteractions();

        showBboxesCheckbox.selectedProperty().addListener((obs, oldVal, newVal) -> redrawCanvas());

        docSelectorComboBox.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) {
                loadDocument(newVal);
            }
        });

        viewerScrollPane.viewportBoundsProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null && newVal.getWidth() > 100 && zoomFactor == 0.45) {
                onFitWidth();
            }
        });

        refreshDocumentList();
        refreshTemplates();
    }

    private void setupZoomAndScrollInteractions() {
        viewerScrollPane.setOnScroll(event -> {
            if (event.isControlDown()) {
                if (event.getDeltaY() > 0) {
                    onZoomIn();
                } else if (event.getDeltaY() < 0) {
                    onZoomOut();
                }
                event.consume();
            }
        });

        viewerScrollPane.setOnMousePressed(event -> {
            if (event.getButton() == MouseButton.SECONDARY || event.getButton() == MouseButton.MIDDLE) {
                dragStartX = event.getX();
                dragStartY = event.getY();
            }
        });

        viewerScrollPane.setOnMouseDragged(event -> {
            if (event.getButton() == MouseButton.SECONDARY || event.getButton() == MouseButton.MIDDLE) {
                double deltaX = dragStartX - event.getX();
                double deltaY = dragStartY - event.getY();
                viewerScrollPane.setHvalue(viewerScrollPane.getHvalue() + deltaX / viewerScrollPane.getWidth());
                viewerScrollPane.setVvalue(viewerScrollPane.getVvalue() + deltaY / viewerScrollPane.getHeight());
                dragStartX = event.getX();
                dragStartY = event.getY();
            }
        });
    }

    private void setupComboBoxes() {
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
    }

    public void refreshDocumentList() {
        AppContext ctx = AppContext.getInstance();
        List<DocumentEntity> docs = ctx.getDocumentService().listDocuments();
        docSelectorComboBox.setItems(FXCollections.observableArrayList(docs));
        if (!docs.isEmpty()) {
            docSelectorComboBox.getSelectionModel().select(0);
        }
    }

    public void refreshTemplates() {
        AppContext ctx = AppContext.getInstance();
        List<TemplateEntity> list = currentDocument == null
                ? ctx.getTemplateService().listTemplates()
                : ctx.getTemplateService().listTemplatesForDocument(
                        currentDocument.getDocumentType(), currentDocument.getRawText());
        templateComboBox.setItems(FXCollections.observableArrayList(list));
        if (!list.isEmpty()) {
            if (currentDocument != null) {
                int topScore = ctx.getTemplateService().compatibilityScore(
                        list.get(0), currentDocument.getDocumentType(), currentDocument.getRawText());
                if (topScore > 0) {
                    templateComboBox.getSelectionModel().select(0);
                } else {
                    templateComboBox.getSelectionModel().clearSelection();
                }
            } else {
                templateComboBox.getSelectionModel().select(0);
            }
        } else {
            templateComboBox.getSelectionModel().clearSelection();
        }
    }

    public void loadDocument(DocumentEntity doc) {
        this.currentDocument = doc;
        this.currentPageIndex = 0;
        this.highlightedField = null;
        this.selectedBlockIndex = -1;
        showBboxesCheckbox.setSelected(false);
        this.exportResultBox.setVisible(false);

        docStatusBadge.setText("Status: " + doc.getStatus());
        docConfidenceBadge.setText(String.format("Confidence: %.1f%%", doc.getConfidence() * 100));

        pagesData.clear();
        tablesData.clear();
        if (doc.getPagesJson() != null && !doc.getPagesJson().trim().isEmpty()) {
            try {
                pagesData = objectMapper.readValue(doc.getPagesJson(), new TypeReference<List<Map<String, Object>>>() {});
            } catch (Exception e) {
                logger.error("Failed to parse pages JSON", e);
            }
        }
        if (doc.getTablesJson() != null && !doc.getTablesJson().trim().isEmpty()) {
            try {
                tablesData = objectMapper.readValue(doc.getTablesJson(), new TypeReference<List<Map<String, Object>>>() {});
            } catch (Exception e) {
                logger.error("Failed to parse tables JSON", e);
            }
        }

        renderCurrentPage();
        onFitWidth();
        populateStructuredFields();
        populateTables();
        populateTextBlocks();
        populateJsonAndRawText();
        refreshTemplates();
    }

    private void renderCurrentPage() {
        int totalPages = Math.max(1, pagesData.size());
        pageLabel.setText(String.format("%d / %d", currentPageIndex + 1, totalPages));

        String imgPath = null;
        if (!pagesData.isEmpty() && currentPageIndex < pagesData.size()) {
            Map<String, Object> pageMap = pagesData.get(currentPageIndex);
            imgPath = (String) pageMap.get("image_path");
        }

        if (imgPath == null && currentDocument != null) {
            String ext = currentDocument.getFileType().toLowerCase();
            if (List.of("png", "jpg", "jpeg", "bmp").contains(ext)) {
                imgPath = currentDocument.getSourcePath();
            }
        }

        if (imgPath != null && new File(imgPath).exists()) {
            try (FileInputStream fis = new FileInputStream(imgPath)) {
                Image img = new Image(fis);
                originalImgWidth = img.getWidth() > 0 ? img.getWidth() : 1488;
                originalImgHeight = img.getHeight() > 0 ? img.getHeight() : 2105;
                pageImageView.setImage(img);
            } catch (Exception e) {
                logger.error("Failed to load page image from {}", imgPath, e);
            }
        } else {
            pageImageView.setImage(null);
        }

        updateZoomAndCanvasSize();
    }

    private void updateZoomAndCanvasSize() {
        zoomLabel.setText(String.format("%d%%", (int)(zoomFactor * 100)));

        double displayWidth = originalImgWidth * zoomFactor;
        double displayHeight = originalImgHeight * zoomFactor;

        pageImageView.setFitWidth(displayWidth);
        pageImageView.setFitHeight(displayHeight);

        bboxCanvas.setWidth(displayWidth);
        bboxCanvas.setHeight(displayHeight);

        redrawCanvas();
    }

    private void redrawCanvas() {
        GraphicsContext gc = bboxCanvas.getGraphicsContext2D();
        gc.clearRect(0, 0, bboxCanvas.getWidth(), bboxCanvas.getHeight());

        if (currentDocument == null) {
            return;
        }

        double scaleX = bboxCanvas.getWidth() / originalImgWidth;
        double scaleY = bboxCanvas.getHeight() / originalImgHeight;

        // 1. Draw Page Text Blocks
        if (!pagesData.isEmpty() && currentPageIndex < pagesData.size()) {
            Map<String, Object> pageMap = pagesData.get(currentPageIndex);
            List<?> blocks = (List<?>) pageMap.get("blocks");
            if (blocks != null) {
                for (int blockIndex = 0; blockIndex < blocks.size(); blockIndex++) {
                    Object bObj = blocks.get(blockIndex);
                    if (bObj instanceof Map<?, ?> bMap) {
                        Map<?, ?> bbox = (Map<?, ?>) bMap.get("bbox");
                        if (bbox != null) {
                            double x0 = getDouble(bbox.get("x0")) * scaleX;
                            double y0 = getDouble(bbox.get("y0")) * scaleY;
                            double x1 = getDouble(bbox.get("x1")) * scaleX;
                            double y1 = getDouble(bbox.get("y1")) * scaleY;

                            double w = Math.max(x1 - x0, 8);
                            double h = Math.max(y1 - y0, 8);

                            boolean selected = blockIndex == selectedBlockIndex;
                            if (!showBboxesCheckbox.isSelected() && !selected) continue;

                            gc.setStroke(Color.web(selected ? "#f59e0b" : "#3b82f6", selected ? 1.0 : 0.72));
                            gc.setLineWidth(selected ? 3.0 : 1.25);
                            gc.strokeRect(x0, y0, w, h);

                            gc.setFill(Color.web(selected ? "#f59e0b" : "#3b82f6", selected ? 0.20 : 0.06));
                            gc.fillRect(x0, y0, w, h);

                            String tag = (String) bMap.get("tag");
                            if (tag != null && zoomFactor >= 0.35 && selected) {
                                gc.setFill(Color.web("#d97706"));
                                String blockLabel = "✓ " + tag;
                                double labelWidth = Math.max(42, blockLabel.length() * 7 + 8);
                                gc.fillRoundRect(x0, Math.max(0, y0 - 16), labelWidth, 15, 4, 4);
                                gc.setFill(Color.WHITE);
                                gc.fillText(blockLabel, x0 + 4, Math.max(11, y0 - 5));
                            }
                        }
                    }
                }
            }
        }

        // 2. Draw Highlighted Structured Fields
        for (DocumentFieldEntity f : currentDocument.getFields()) {
            if (f.getSourceBboxJson() != null && !f.getSourceBboxJson().isEmpty()) {
                try {
                    Map<String, Object> bbox = objectMapper.readValue(f.getSourceBboxJson(), new TypeReference<>() {});
                    double x0 = getDouble(bbox.get("x0")) * scaleX;
                    double y0 = getDouble(bbox.get("y0")) * scaleY;
                    double x1 = getDouble(bbox.get("x1")) * scaleX;
                    double y1 = getDouble(bbox.get("y1")) * scaleY;

                    double w = Math.max(x1 - x0, 10);
                    double h = Math.max(y1 - y0, 10);

                    boolean isHighlighted = f.getFieldName().equalsIgnoreCase(highlightedField);
                    if (!showBboxesCheckbox.isSelected() && !isHighlighted) continue;

                    if (isHighlighted) {
                        gc.setStroke(Color.web("#d97706", 1.0));
                        gc.setLineWidth(3.0);
                        gc.strokeRect(x0 - 2, y0 - 2, w + 4, h + 4);
                        gc.setFill(Color.web("#f59e0b", 0.25));
                        gc.fillRect(x0, y0, w, h);
                    } else {
                        gc.setStroke(Color.web("#059669", 0.9));
                        gc.setLineWidth(2.0);
                        gc.strokeRect(x0, y0, w, h);
                        gc.setFill(Color.web("#10b981", 0.12));
                        gc.fillRect(x0, y0, w, h);
                    }

                    String lbl = f.getLabel() != null ? f.getLabel() : f.getFieldName();
                    if (zoomFactor >= 0.35) {
                        gc.setFill(isHighlighted ? Color.web("#d97706") : Color.web("#059669"));
                        gc.fillRoundRect(x0, Math.max(0, y0 - 15), lbl.length() * 7 + 8, 14, 4, 4);
                        gc.setFill(Color.WHITE);
                        gc.fillText(lbl, x0 + 4, Math.max(10, y0 - 4));
                    }

                } catch (Exception ignored) {}
            }
        }
    }

    private void setupCanvasInteractions() {
        bboxCanvas.setOnMouseClicked(event -> {
            double clickX = event.getX();
            double clickY = event.getY();
            double scaleX = bboxCanvas.getWidth() / originalImgWidth;
            double scaleY = bboxCanvas.getHeight() / originalImgHeight;

            int clickedBlock = findBlockAt(clickX, clickY, scaleX, scaleY);
            if (clickedBlock >= 0) {
                selectTextBlock(clickedBlock, true);
                return;
            }

            if (currentDocument != null) {
                for (DocumentFieldEntity f : currentDocument.getFields()) {
                    if (f.getSourceBboxJson() != null) {
                        try {
                            Map<String, Object> bbox = objectMapper.readValue(f.getSourceBboxJson(), new TypeReference<>() {});
                            double x0 = getDouble(bbox.get("x0")) * scaleX;
                            double y0 = getDouble(bbox.get("y0")) * scaleY;
                            double x1 = getDouble(bbox.get("x1")) * scaleX;
                            double y1 = getDouble(bbox.get("y1")) * scaleY;

                            if (clickX >= x0 && clickX <= x1 && clickY >= y0 && clickY <= y1) {
                                highlightField(f.getFieldName());
                                return;
                            }
                        } catch (Exception ignored) {}
                    }
                }
            }
        });
    }

    private int findBlockAt(double x, double y, double scaleX, double scaleY) {
        if (pagesData.isEmpty() || currentPageIndex >= pagesData.size()) return -1;
        Object rawBlocks = pagesData.get(currentPageIndex).get("blocks");
        if (!(rawBlocks instanceof List<?> blocks)) return -1;

        int match = -1;
        double smallestArea = Double.MAX_VALUE;
        for (int i = 0; i < blocks.size(); i++) {
            if (!(blocks.get(i) instanceof Map<?, ?> block)) continue;
            if (!(block.get("bbox") instanceof Map<?, ?> bbox)) continue;
            double x0 = getDouble(bbox.get("x0")) * scaleX;
            double y0 = getDouble(bbox.get("y0")) * scaleY;
            double x1 = getDouble(bbox.get("x1")) * scaleX;
            double y1 = getDouble(bbox.get("y1")) * scaleY;
            if (x >= x0 && x <= x1 && y >= y0 && y <= y1) {
                double area = Math.max(1, (x1 - x0) * (y1 - y0));
                if (area < smallestArea) {
                    smallestArea = area;
                    match = i;
                }
            }
        }
        return match;
    }

    private void selectTextBlock(int index, boolean scrollToCard) {
        if (index < 0 || index >= blockCardList.size()) return;
        selectedBlockIndex = index;
        highlightedField = null;
        for (int i = 0; i < blockCardList.size(); i++) {
            VBox card = blockCardList.get(i);
            card.getStyleClass().remove("block-card-selected");
            if (i == index) card.getStyleClass().add("block-card-selected");
        }
        redrawCanvas();
        parsingTabPane.getSelectionModel().select(0);

        if (scrollToCard) {
            Platform.runLater(() -> {
                Node card = blockCardList.get(index);
                double contentHeight = blocksContainer.getBoundsInLocal().getHeight();
                double viewportHeight = parsingScrollPane.getViewportBounds().getHeight();
                double maxScroll = Math.max(1, contentHeight - viewportHeight);
                double target = card.getBoundsInParent().getMinY() / maxScroll;
                parsingScrollPane.setVvalue(Math.max(0, Math.min(1, target)));
                card.requestFocus();
            });
        }
    }

    private void highlightField(String fieldName) {
        this.highlightedField = fieldName;
        redrawCanvas();

        TextField tf = fieldInputMap.get(fieldName);
        if (tf != null) {
            tf.requestFocus();
            tf.setStyle("-fx-border-color: #d97706; -fx-border-width: 2; -fx-background-color: #fffbeb;");
        }
    }

    private void populateStructuredFields() {
        fieldsContainer.getChildren().clear();
        fieldInputMap.clear();

        if (currentDocument == null || currentDocument.getFields().isEmpty()) {
            Label emptyLbl = new Label("Tài liệu dạng công văn / văn bản tổng quát. Toàn bộ nội dung nằm ở danh sách khối bên dưới.");
            emptyLbl.setStyle("-fx-text-fill: #64748b; -fx-padding: 8; -fx-font-style: italic;");
            fieldsContainer.getChildren().add(emptyLbl);
            return;
        }

        for (DocumentFieldEntity f : currentDocument.getFields()) {
            VBox card = new VBox(5);
            card.getStyleClass().add("block-card");

            HBox header = new HBox(8);
            header.setAlignment(Pos.CENTER_LEFT);

            Label nameLbl = new Label(f.getLabel() != null ? f.getLabel() : f.getFieldName());
            nameLbl.setStyle("-fx-font-weight: bold; -fx-text-fill: #1e293b;");

            Label tagPill = new Label("Field");
            tagPill.getStyleClass().add("badge-tag-field");

            Label confPill = new Label(String.format("%.0f%%", f.getConfidence() * 100));
            confPill.getStyleClass().add("badge-conf");

            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);

            Button focusBtn = new Button("Xem trên ảnh");
            focusBtn.getStyleClass().add("tool-btn");
            focusBtn.setOnAction(e -> highlightField(f.getFieldName()));

            Button copyBtn = new Button("Sao chép");
            copyBtn.getStyleClass().add("tool-btn");
            copyBtn.setOnAction(e -> copyToClipboard(f.getFieldValue()));

            header.getChildren().addAll(tagPill, nameLbl, confPill, spacer, focusBtn, copyBtn);

            TextField valField = new TextField(f.getFieldValue() != null ? f.getFieldValue() : "");
            valField.setPromptText("Nhập giá trị chuẩn hóa...");
            fieldInputMap.put(f.getFieldName(), valField);

            valField.textProperty().addListener((obs, oldVal, newVal) -> {
                f.setFieldValue(newVal);
                f.setValidated(true);
            });

            card.getChildren().addAll(header, valField);
            fieldsContainer.getChildren().add(card);
        }
    }

    private void populateTextBlocks() {
        blocksContainer.getChildren().clear();
        blockInputList.clear();
        blockCardList.clear();
        selectedBlockIndex = -1;

        if (pagesData.isEmpty() || currentPageIndex >= pagesData.size()) {
            blocksCountLabel.setText("0 khối");
            return;
        }

        Map<String, Object> pageMap = pagesData.get(currentPageIndex);
        List<?> blocks = (List<?>) pageMap.get("blocks");
        int count = blocks != null ? blocks.size() : 0;
        blocksCountLabel.setText(count + " khối");

        if (blocks != null) {
            for (int i = 0; i < blocks.size(); i++) {
                if (blocks.get(i) instanceof Map<?, ?> bMap) {
                    VBox card = new VBox(6);
                    card.getStyleClass().add("block-card");
                    card.setFocusTraversable(true);

                    HBox header = new HBox(8);
                    header.setAlignment(Pos.CENTER_LEFT);

                    String tag = (String) bMap.get("tag");
                    Label tagPill = new Label(tag != null ? tag : "Text");
                    tagPill.getStyleClass().add("badge-tag");

                    Label idxLbl = new Label("Block #" + (i + 1));
                    idxLbl.setStyle("-fx-font-weight: bold; -fx-text-fill: #64748b;");

                    Region spacer = new Region();
                    HBox.setHgrow(spacer, Priority.ALWAYS);

                    String text = (String) bMap.get("text");

                    Button copyBtn = new Button("Sao chép");
                    copyBtn.getStyleClass().add("tool-btn");
                    copyBtn.setOnAction(e -> copyToClipboard(text));

                    header.getChildren().addAll(tagPill, idxLbl, spacer, copyBtn);

                    TextArea blockArea = new TextArea(text != null ? text : "");
                    blockArea.setPrefRowCount(Math.min(6, Math.max(2, (text != null ? text.split("\n").length : 1))));
                    blockArea.setWrapText(true);
                    blockInputList.add(blockArea);

                    card.getChildren().addAll(header, blockArea);
                    final int blockIndex = i;
                    card.setOnMouseClicked(e -> {
                        if (e.getButton() == MouseButton.PRIMARY) selectTextBlock(blockIndex, false);
                    });
                    blockCardList.add(card);
                    blocksContainer.getChildren().add(card);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void populateTables() {
        tablesContainer.getChildren().clear();
        tablesCountLabel.setText(tablesData.size() + " bảng");
        if (tablesData.isEmpty()) {
            Label empty = new Label("Không phát hiện bảng có cấu trúc trong tài liệu này.");
            empty.setStyle("-fx-text-fill: #64748b; -fx-font-style: italic; -fx-padding: 4 8;");
            tablesContainer.getChildren().add(empty);
            return;
        }

        for (int tableIndex = 0; tableIndex < tablesData.size(); tableIndex++) {
            Map<String, Object> table = tablesData.get(tableIndex);
            List<Object> headers = mutableList(table, "headers");
            List<Object> rows = mutableList(table, "rows");
            List<Map<String, Object>> cells = table.get("cells") instanceof List<?> rawCells
                    ? (List<Map<String, Object>>) (List<?>) rawCells : new ArrayList<>();

            VBox card = new VBox(7);
            card.getStyleClass().add("block-card");
            HBox titleRow = new HBox(8);
            titleRow.setAlignment(Pos.CENTER_LEFT);
            Label badge = new Label("Table");
            badge.getStyleClass().add("badge-tag-field");
            Label title = new Label(String.valueOf(table.getOrDefault("name", "Bảng " + (tableIndex + 1))));
            title.setStyle("-fx-font-weight: bold; -fx-text-fill: #1e293b;");
            double tableConfidence = getDouble(table.get("confidence"));
            Label confidence = new Label(String.format("%.0f%%", tableConfidence * 100));
            confidence.getStyleClass().add(tableConfidence < .85 ? "badge-warning" : "badge-conf");
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            Label hint = new Label(tableConfidence < .85 ? "⚠ Cần đối chiếu" : "✓ Đã nhận dạng");
            hint.setStyle(tableConfidence < .85 ? "-fx-text-fill: #b45309;" : "-fx-text-fill: #059669;");
            titleRow.getChildren().addAll(badge, title, confidence, spacer, hint);

            int columnCount = headers.size();
            for (Object row : rows) {
                if (row instanceof List<?> list) columnCount = Math.max(columnCount, list.size());
            }
            GridPane grid = new GridPane();
            grid.setHgap(4);
            grid.setVgap(4);
            for (int col = 0; col < columnCount; col++) {
                TextField editor = new TextField(col < headers.size() ? String.valueOf(headers.get(col)) : "");
                editor.setStyle("-fx-font-weight: bold; -fx-background-color: #eff6ff;");
                final int column = col;
                editor.textProperty().addListener((obs, oldValue, newValue) -> setListValue(headers, column, newValue));
                grid.add(editor, col, 0);
                GridPane.setHgrow(editor, Priority.ALWAYS);
                ColumnConstraints constraints = new ColumnConstraints(110, 170, Double.MAX_VALUE);
                constraints.setHgrow(Priority.ALWAYS);
                grid.getColumnConstraints().add(constraints);
            }
            for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
                List<Object> row = rows.get(rowIndex) instanceof List<?> list
                        ? (List<Object>) list : new ArrayList<>();
                if (!(rows.get(rowIndex) instanceof List<?>)) rows.set(rowIndex, row);
                for (int col = 0; col < columnCount; col++) {
                    TextField editor = new TextField(col < row.size() ? String.valueOf(row.get(col)) : "");
                    double cellConfidence = findCellConfidence(cells, rowIndex + 1, col);
                    if (cellConfidence > 0 && cellConfidence < .85) {
                        editor.setStyle("-fx-border-color: #f59e0b; -fx-background-color: #fffbeb;");
                        editor.setTooltip(new Tooltip(String.format("OCR %.1f%% — cần đối chiếu ảnh gốc", cellConfidence * 100)));
                    }
                    final int rowNumber = rowIndex;
                    final int column = col;
                    editor.textProperty().addListener((obs, oldValue, newValue) -> {
                        setListValue(row, column, newValue);
                        updateCell(cells, rowNumber + 1, column, newValue);
                    });
                    grid.add(editor, col, rowIndex + 1);
                    GridPane.setHgrow(editor, Priority.ALWAYS);
                }
            }
            ScrollPane tableScroll = new ScrollPane(grid);
            tableScroll.setFitToHeight(true);
            tableScroll.setPannable(true);
            tableScroll.setStyle("-fx-background-color: transparent;");
            card.getChildren().addAll(titleRow, tableScroll);
            tablesContainer.getChildren().add(card);
        }
    }

    @SuppressWarnings("unchecked")
    private List<Object> mutableList(Map<String, Object> owner, String key) {
        Object value = owner.get(key);
        if (value instanceof List<?>) return (List<Object>) value;
        List<Object> list = new ArrayList<>();
        owner.put(key, list);
        return list;
    }

    private void setListValue(List<Object> list, int index, String value) {
        while (list.size() <= index) list.add("");
        list.set(index, value);
    }

    private double findCellConfidence(List<Map<String, Object>> cells, int rowIndex, int colIndex) {
        for (Map<String, Object> cell : cells) {
            int row = (int) getDouble(cell.getOrDefault("row_index", cell.get("row")));
            int col = (int) getDouble(cell.getOrDefault("col_index", cell.get("col")));
            if (row == rowIndex && col == colIndex) return getDouble(cell.get("confidence"));
        }
        return 0;
    }

    private void updateCell(List<Map<String, Object>> cells, int rowIndex, int colIndex, String value) {
        for (Map<String, Object> cell : cells) {
            int row = (int) getDouble(cell.getOrDefault("row_index", cell.get("row")));
            int col = (int) getDouble(cell.getOrDefault("col_index", cell.get("col")));
            if (row == rowIndex && col == colIndex) {
                cell.put("text", value);
                cell.put("confidence", 1.0);
                return;
            }
        }
    }

    private void populateJsonAndRawText() {
        if (currentDocument == null) {
            jsonTextArea.clear();
            rawTextArea.clear();
            return;
        }

        try {
            Map<String, Object> jsonDoc = new HashMap<>();
            jsonDoc.put("id", currentDocument.getId());
            jsonDoc.put("document_type", currentDocument.getDocumentType());
            jsonDoc.put("status", currentDocument.getStatus());
            jsonDoc.put("confidence", currentDocument.getConfidence());

            Map<String, Object> fieldsMap = new HashMap<>();
            for (DocumentFieldEntity f : currentDocument.getFields()) {
                fieldsMap.put(f.getFieldName(), f.getFieldValue());
            }
            jsonDoc.put("fields", fieldsMap);
            jsonDoc.put("pages", pagesData);
            jsonDoc.put("tables", tablesData);

            jsonTextArea.setText(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(jsonDoc));
        } catch (Exception e) {
            jsonTextArea.setText("{}");
        }

        rawTextArea.setText(currentDocument.getRawText() != null ? currentDocument.getRawText() : "");
    }

    @FXML
    public void onPrevPage() {
        showToolbarLabel("Trang trước");
        if (currentPageIndex > 0) {
            currentPageIndex--;
            selectedBlockIndex = -1;
            renderCurrentPage();
            populateTextBlocks();
        }
    }

    @FXML
    public void onNextPage() {
        showToolbarLabel("Trang sau");
        if (currentPageIndex < pagesData.size() - 1) {
            currentPageIndex++;
            selectedBlockIndex = -1;
            renderCurrentPage();
            populateTextBlocks();
        }
    }

    @FXML
    public void onZoomIn() {
        showToolbarLabel("Phóng to");
        if (zoomFactor < 3.0) {
            zoomFactor = Math.min(3.0, zoomFactor + 0.15);
            updateZoomAndCanvasSize();
        }
    }

    @FXML
    public void onZoomOut() {
        showToolbarLabel("Thu nhỏ");
        if (zoomFactor > 0.15) {
            zoomFactor = Math.max(0.15, zoomFactor - 0.15);
            updateZoomAndCanvasSize();
        }
    }

    @FXML
    public void onZoomReset() {
        showToolbarLabel("Kích thước 100%");
        zoomFactor = 1.0;
        updateZoomAndCanvasSize();
    }

    @FXML
    public void onFitWidth() {
        showToolbarLabel("Vừa rộng theo chiều ngang");
        Platform.runLater(() -> {
            double viewportWidth = viewerScrollPane.getViewportBounds().getWidth();
            if (viewportWidth > 50 && originalImgWidth > 0) {
                zoomFactor = Math.max(0.2, (viewportWidth - 25) / originalImgWidth);
                updateZoomAndCanvasSize();
            }
        });
    }

    @FXML
    public void onFitPage() {
        showToolbarLabel("Vừa toàn bộ trang");
        Platform.runLater(() -> {
            double viewportWidth = viewerScrollPane.getViewportBounds().getWidth();
            double viewportHeight = viewerScrollPane.getViewportBounds().getHeight();
            if (viewportWidth > 50 && viewportHeight > 50 && originalImgWidth > 0 && originalImgHeight > 0) {
                double scaleW = (viewportWidth - 25) / originalImgWidth;
                double scaleH = (viewportHeight - 25) / originalImgHeight;
                zoomFactor = Math.max(0.15, Math.min(scaleW, scaleH));
                updateZoomAndCanvasSize();
            }
        });
    }

    @FXML
    public void onRotateLeft() {
        showToolbarLabel("Xoay trái 90 độ");
        rotateCurrentImage(-90);
    }

    @FXML
    public void onRotateRight() {
        showToolbarLabel("Xoay phải 90 độ");
        rotateCurrentImage(90);
    }

    private void rotateCurrentImage(int angleDegrees) {
        if (currentDocument == null) {
            statusMessageLabel.setText("Vui lòng chọn một tài liệu trước khi xoay.");
            return;
        }

        String imgPath = null;
        if (!pagesData.isEmpty() && currentPageIndex < pagesData.size()) {
            Map<String, Object> pageMap = pagesData.get(currentPageIndex);
            imgPath = (String) pageMap.get("image_path");
        }
        if (imgPath == null) {
            imgPath = currentDocument.getSourcePath();
        }

        if (imgPath == null || !new File(imgPath).exists()) {
            statusMessageLabel.setText("Không tìm thấy tệp ảnh của trang hiện tại.");
            return;
        }

        try {
            File imgFile = new File(imgPath);
            rotateImageFileOnDisk(imgFile, angleDegrees);

            // Re-extraction must use the oriented cache image, never alter the file the
            // user originally imported. This also makes repeated extraction idempotent.
            String fileType = currentDocument.getFileType() == null
                    ? "" : currentDocument.getFileType().toLowerCase(Locale.ROOT);
            if (List.of("png", "jpg", "jpeg", "bmp", "tif", "tiff").contains(fileType)) {
                currentDocument.setSourcePath(imgFile.getAbsolutePath());
                AppContext.getInstance().getDocumentRepository().save(currentDocument);
            }

            renderCurrentPage();
            onFitWidth();

            statusMessageLabel.setText("Đã xoay ảnh " + (angleDegrees > 0 ? "phải 90°" : "trái 90°") + ". Đang bóc tách lại...");
            onReExtract();
        } catch (Exception e) {
            logger.error("Lỗi khi xoay ảnh", e);
            statusMessageLabel.setText("Lỗi khi xoay ảnh: " + e.getMessage());
        }
    }

    private void showToolbarLabel(String label) {
        if (statusMessageLabel != null) {
            statusMessageLabel.setText("Đã chọn: " + label);
        }
    }

    private void rotateImageFileOnDisk(File file, int angleDegrees) throws IOException {
        if (file == null || !file.exists()) return;
        BufferedImage src = ImageIO.read(file);
        if (src == null) return;

        int w = src.getWidth();
        int h = src.getHeight();

        boolean swapDims = Math.abs(angleDegrees % 180) == 90;
        int newW = swapDims ? h : w;
        int newH = swapDims ? w : h;

        int imageType = src.getType();
        if (imageType == 0 || imageType == BufferedImage.TYPE_CUSTOM) {
            imageType = BufferedImage.TYPE_INT_RGB;
        }

        String formatName = "jpg";
        int dotIdx = file.getName().lastIndexOf('.');
        if (dotIdx > 0) {
            String ext = file.getName().substring(dotIdx + 1).toLowerCase();
            if ("png".equals(ext)) {
                formatName = "png";
                if (imageType != BufferedImage.TYPE_INT_ARGB) {
                    imageType = BufferedImage.TYPE_INT_ARGB;
                }
            } else if ("bmp".equals(ext)) {
                formatName = "bmp";
            }
        }

        BufferedImage dest = new BufferedImage(newW, newH, imageType);
        Graphics2D g2d = dest.createGraphics();
        try {
            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            AffineTransform tx = new AffineTransform();
            if (angleDegrees == 90 || angleDegrees == -270) {
                tx.translate(h, 0);
                tx.quadrantRotate(1);
            } else if (angleDegrees == -90 || angleDegrees == 270) {
                tx.translate(0, w);
                tx.quadrantRotate(3);
            } else if (Math.abs(angleDegrees) == 180) {
                tx.translate(w, h);
                tx.quadrantRotate(2);
            }
            g2d.setTransform(tx);
            g2d.drawImage(src, 0, 0, null);
        } finally {
            g2d.dispose();
        }

        ImageIO.write(dest, formatName, file);
    }

    @FXML
    public void onReExtract() {
        if (currentDocument == null) return;

        statusMessageLabel.setText("Đang trích xuất lại tài liệu bằng RapidOCR...");
        AppContext ctx = AppContext.getInstance();
        ctx.getExtractionService().extractDocument(currentDocument.getId())
                .thenAccept(doc -> Platform.runLater(() -> {
                    statusMessageLabel.setText("Bóc tách thành công! Status: " + doc.getStatus());
                    loadDocument(doc);
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> statusMessageLabel.setText("Lỗi bóc tách: " + ex.getMessage()));
                    return null;
                });
    }

    @FXML
    public void onSaveChanges() {
        if (currentDocument == null) return;

        AppContext ctx = AppContext.getInstance();
        for (DocumentFieldEntity f : currentDocument.getFields()) {
            TextField tf = fieldInputMap.get(f.getFieldName());
            if (tf != null) {
                f.setFieldValue(tf.getText());
                f.setValidated(true);
                f.setValidationError(null);
            }
        }
        try {
            currentDocument.setTablesJson(objectMapper.writeValueAsString(tablesData));
        } catch (Exception e) {
            statusMessageLabel.setText("Không thể lưu dữ liệu bảng: " + e.getMessage());
            return;
        }
        currentDocument.setStatus("VALIDATED");
        ctx.getDocumentRepository().save(currentDocument);

        statusMessageLabel.setText("Đã lưu thay đổi và cập nhật cơ sở dữ liệu thành công.");
        loadDocument(currentDocument);
    }

    @FXML
    public void onExportFullWord() {
        if (currentDocument == null) {
            statusMessageLabel.setText("Vui lòng chọn một tài liệu.");
            return;
        }

        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Lưu văn bản Word OCR toàn văn");
        String baseName = currentDocument.getFilename().replaceAll("(?i)\\.[a-z0-9]+$", "");
        fileChooser.setInitialFileName("Trich_xuat_" + baseName + ".docx");
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Microsoft Word (*.docx)", "*.docx"));

        File selectedFile = fileChooser.showSaveDialog(pageImageView.getScene().getWindow());
        if (selectedFile == null) {
            statusMessageLabel.setText("Đã hủy thao tác lưu file Word.");
            return;
        }

        statusMessageLabel.setText("Đang xuất toàn văn tài liệu ra Word...");
        AppContext ctx = AppContext.getInstance();
        ctx.getExportService().exportFullDocument(currentDocument.getId(), "word", selectedFile.getAbsolutePath())
                .thenAccept(outputPath -> Platform.runLater(() -> {
                    this.lastExportedPath = outputPath;
                    statusMessageLabel.setText("Đã lưu file Word thành công tại: " + outputPath);
                    exportResultBox.setVisible(true);
                    currentDocument.setStatus("EXPORTED");
                    docStatusBadge.setText("Status: EXPORTED");
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> statusMessageLabel.setText("Lỗi xuất Word: " + ex.getMessage()));
                    return null;
                });
    }

    @FXML
    public void onExportFullExcel() {
        if (currentDocument == null) {
            statusMessageLabel.setText("Vui lòng chọn một tài liệu.");
            return;
        }

        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Lưu bảng tính Excel OCR toàn bộ");
        String baseName = currentDocument.getFilename().replaceAll("(?i)\\.[a-z0-9]+$", "");
        fileChooser.setInitialFileName("Bang_tinh_" + baseName + ".xlsx");
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Microsoft Excel (*.xlsx)", "*.xlsx"));

        File selectedFile = fileChooser.showSaveDialog(pageImageView.getScene().getWindow());
        if (selectedFile == null) {
            statusMessageLabel.setText("Đã hủy thao tác lưu file Excel.");
            return;
        }

        statusMessageLabel.setText("Đang xuất toàn bộ bảng tính và dữ liệu OCR ra Excel...");
        AppContext ctx = AppContext.getInstance();
        ctx.getExportService().exportFullDocument(currentDocument.getId(), "excel", selectedFile.getAbsolutePath())
                .thenAccept(outputPath -> Platform.runLater(() -> {
                    this.lastExportedPath = outputPath;
                    statusMessageLabel.setText("Đã lưu file Excel thành công tại: " + outputPath);
                    exportResultBox.setVisible(true);
                    currentDocument.setStatus("EXPORTED");
                    docStatusBadge.setText("Status: EXPORTED");
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> statusMessageLabel.setText("Lỗi xuất Excel: " + ex.getMessage()));
                    return null;
                });
    }

    @FXML
    public void onExportDocument() {
        if (currentDocument == null) {
            statusMessageLabel.setText("Vui lòng chọn một tài liệu.");
            return;
        }

        TemplateEntity tpl = templateComboBox.getSelectionModel().getSelectedItem();
        if (tpl == null) {
            statusMessageLabel.setText("Vui lòng chọn mẫu biểu Excel/Word.");
            return;
        }

        String ext = "excel".equalsIgnoreCase(tpl.getTemplateType()) ? "xlsx" : "docx";
        String baseName = currentDocument.getFilename().replaceAll("(?i)\\.[a-z0-9]+$", "");

        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Lưu file điền biểu mẫu " + tpl.getName());
        fileChooser.setInitialFileName("Mau_" + baseName + "." + ext);
        if ("xlsx".equals(ext)) {
            fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Microsoft Excel (*.xlsx)", "*.xlsx"));
        } else {
            fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Microsoft Word (*.docx)", "*.docx"));
        }

        File selectedFile = fileChooser.showSaveDialog(pageImageView.getScene().getWindow());
        if (selectedFile == null) {
            statusMessageLabel.setText("Đã hủy thao tác lưu file biểu mẫu.");
            return;
        }

        statusMessageLabel.setText("Đang điền dữ liệu và xuất file " + tpl.getTemplateType() + "...");
        exportButton.setDisable(true);

        AppContext ctx = AppContext.getInstance();
        ctx.getExportService().exportDocument(currentDocument.getId(), tpl.getId(), selectedFile.getAbsolutePath())
                .thenAccept(outputPath -> Platform.runLater(() -> {
                    this.lastExportedPath = outputPath;
                    statusMessageLabel.setText("Đã lưu file thành công tại: " + outputPath);
                    exportResultBox.setVisible(true);
                    exportButton.setDisable(false);
                    currentDocument.setStatus("EXPORTED");
                    docStatusBadge.setText("Status: EXPORTED");
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> {
                        statusMessageLabel.setText("Lỗi xuất file: " + ex.getMessage());
                        exportButton.setDisable(false);
                    });
                    return null;
                });
    }

    @FXML
    public void onOpenExportedFile() {
        if (lastExportedPath != null) {
            File f = new File(lastExportedPath);
            if (f.exists()) {
                try {
                    Desktop.getDesktop().open(f);
                    statusMessageLabel.setText("Đã mở file: " + f.getName());
                } catch (Exception e) {
                    statusMessageLabel.setText("Không thể mở file tự động: " + e.getMessage());
                }
            } else {
                statusMessageLabel.setText("File không tồn tại: " + lastExportedPath);
            }
        }
    }

    @FXML
    public void onOpenExportFolder() {
        File folder = null;
        if (lastExportedPath != null) {
            File f = new File(lastExportedPath);
            if (f.getParentFile() != null && f.getParentFile().exists()) {
                folder = f.getParentFile();
            }
        }
        if (folder == null) {
            folder = new File("app-data/exports");
            if (!folder.exists()) {
                folder = new File("../app-data/exports");
            }
        }

        try {
            Desktop.getDesktop().open(folder.getAbsoluteFile());
        } catch (Exception e) {
            statusMessageLabel.setText("Lỗi mở thư mục: " + e.getMessage());
        }
    }

    @FXML
    public void onCopyJson() {
        copyToClipboard(jsonTextArea.getText());
        statusMessageLabel.setText("Đã sao chép toàn bộ JSON vào clipboard.");
    }

    @FXML
    public void onCopyRawText() {
        copyToClipboard(rawTextArea.getText());
        statusMessageLabel.setText("Đã sao chép văn bản OCR vào clipboard.");
    }

    private void copyToClipboard(String content) {
        if (content == null) return;
        Clipboard clipboard = Clipboard.getSystemClipboard();
        ClipboardContent cc = new ClipboardContent();
        cc.putString(content);
        clipboard.setContent(cc);
    }

    private double getDouble(Object val) {
        if (val instanceof Number n) return n.doubleValue();
        if (val instanceof String s) {
            try { return Double.parseDouble(s); } catch (Exception ignored) {}
        }
        return 0.0;
    }

    @FXML
    public void onToggleGuide() {
        if (quickGuideBox != null) {
            boolean visible = !quickGuideBox.isVisible();
            quickGuideBox.setVisible(visible);
            quickGuideBox.setManaged(visible);
            if (toggleGuideButton != null) {
                toggleGuideButton.setText(visible ? "✕ Ẩn hướng dẫn" : "💡 Hướng dẫn làm việc");
            }
        }
    }

    @FXML
    public void onCloseGuide() {
        if (quickGuideBox != null) {
            quickGuideBox.setVisible(false);
            quickGuideBox.setManaged(false);
            if (toggleGuideButton != null) {
                toggleGuideButton.setText("💡 Hướng dẫn làm việc");
            }
        }
    }
}
