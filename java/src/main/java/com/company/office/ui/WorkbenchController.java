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
import java.io.File;
import java.io.FileInputStream;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public class WorkbenchController {
    private static final Logger logger = LoggerFactory.getLogger(WorkbenchController.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @FXML private ComboBox<DocumentEntity> docSelectorComboBox;
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
            templateComboBox.getSelectionModel().select(0);
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
        if (doc.getPagesJson() != null && !doc.getPagesJson().trim().isEmpty()) {
            try {
                pagesData = objectMapper.readValue(doc.getPagesJson(), new TypeReference<List<Map<String, Object>>>() {});
            } catch (Exception e) {
                logger.error("Failed to parse pages JSON", e);
            }
        }

        renderCurrentPage();
        onFitWidth();
        populateStructuredFields();
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
                            if (tag != null && zoomFactor >= 0.35) {
                                gc.setFill(Color.web(selected ? "#d97706" : "#2563eb"));
                                String blockLabel = (selected ? "✓ " : "") + tag;
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

            jsonTextArea.setText(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(jsonDoc));
        } catch (Exception e) {
            jsonTextArea.setText("{}");
        }

        rawTextArea.setText(currentDocument.getRawText() != null ? currentDocument.getRawText() : "");
    }

    @FXML
    public void onPrevPage() {
        if (currentPageIndex > 0) {
            currentPageIndex--;
            selectedBlockIndex = -1;
            renderCurrentPage();
            populateTextBlocks();
        }
    }

    @FXML
    public void onNextPage() {
        if (currentPageIndex < pagesData.size() - 1) {
            currentPageIndex++;
            selectedBlockIndex = -1;
            renderCurrentPage();
            populateTextBlocks();
        }
    }

    @FXML
    public void onZoomIn() {
        if (zoomFactor < 3.0) {
            zoomFactor = Math.min(3.0, zoomFactor + 0.15);
            updateZoomAndCanvasSize();
        }
    }

    @FXML
    public void onZoomOut() {
        if (zoomFactor > 0.15) {
            zoomFactor = Math.max(0.15, zoomFactor - 0.15);
            updateZoomAndCanvasSize();
        }
    }

    @FXML
    public void onZoomReset() {
        zoomFactor = 1.0;
        updateZoomAndCanvasSize();
    }

    @FXML
    public void onFitWidth() {
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
    public void onReExtract() {
        if (currentDocument == null) return;

        statusMessageLabel.setText("Đang trích xuất lại tài liệu bằng PaddleOCR...");
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
        TemplateEntity wordTemplate = templateComboBox.getSelectionModel().getSelectedItem();
        if (wordTemplate == null || !"word".equalsIgnoreCase(wordTemplate.getTemplateType())) {
            wordTemplate = templateComboBox.getItems().stream()
                    .filter(t -> "word".equalsIgnoreCase(t.getTemplateType())).findFirst().orElse(null);
        }

        fileChooser.setTitle(wordTemplate == null
                ? "Lưu văn bản Word OCR" : "Xuất Word theo mẫu: " + wordTemplate.getName());
        String baseName = currentDocument.getFilename().replaceAll("(?i)\\.[a-z0-9]+$", "");
        fileChooser.setInitialFileName((wordTemplate == null ? "Trich_xuat_" : "Theo_mau_") + baseName + ".docx");
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Microsoft Word (*.docx)", "*.docx"));

        File selectedFile = fileChooser.showSaveDialog(pageImageView.getScene().getWindow());
        if (selectedFile == null) {
            statusMessageLabel.setText("Đã hủy thao tác lưu file Word.");
            return;
        }

        statusMessageLabel.setText(wordTemplate == null
                ? "Đang xuất OCR thô ra Word..." : "Đang điền dữ liệu vào mẫu " + wordTemplate.getName() + "...");
        AppContext ctx = AppContext.getInstance();
        final TemplateEntity selectedWordTemplate = wordTemplate;
        CompletableFuture<String> exportFuture = selectedWordTemplate == null
                ? ctx.getExportService().exportFullDocument(currentDocument.getId(), "word", selectedFile.getAbsolutePath())
                : ctx.getExportService().exportDocument(currentDocument.getId(), selectedWordTemplate.getId(), selectedFile.getAbsolutePath());
        exportFuture
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

        TemplateEntity excelTemplate = templateComboBox.getSelectionModel().getSelectedItem();
        if (excelTemplate == null || !"excel".equalsIgnoreCase(excelTemplate.getTemplateType())) {
            excelTemplate = templateComboBox.getItems().stream()
                    .filter(t -> "excel".equalsIgnoreCase(t.getTemplateType())).findFirst().orElse(null);
        }

        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle(excelTemplate == null
                ? "Lưu bảng tính Excel OCR" : "Xuất Excel theo mẫu: " + excelTemplate.getName());
        String baseName = currentDocument.getFilename().replaceAll("(?i)\\.[a-z0-9]+$", "");
        fileChooser.setInitialFileName((excelTemplate == null ? "Du_lieu_" : "Theo_mau_") + baseName + ".xlsx");
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Microsoft Excel (*.xlsx)", "*.xlsx"));

        File selectedFile = fileChooser.showSaveDialog(pageImageView.getScene().getWindow());
        if (selectedFile == null) {
            statusMessageLabel.setText("Đã hủy thao tác lưu file Excel.");
            return;
        }

        statusMessageLabel.setText(excelTemplate == null
                ? "Đang xuất dữ liệu OCR thô ra Excel..." : "Đang điền dữ liệu vào mẫu " + excelTemplate.getName() + "...");
        AppContext ctx = AppContext.getInstance();
        final TemplateEntity selectedExcelTemplate = excelTemplate;
        CompletableFuture<String> exportFuture = selectedExcelTemplate == null
                ? ctx.getExportService().exportFullDocument(currentDocument.getId(), "excel", selectedFile.getAbsolutePath())
                : ctx.getExportService().exportDocument(currentDocument.getId(), selectedExcelTemplate.getId(), selectedFile.getAbsolutePath());
        exportFuture
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
}
