package excelo;

import com.formdev.flatlaf.FlatLightLaf;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartPanel;
import org.jfree.chart.JFreeChart;
import org.jfree.data.category.DefaultCategoryDataset;

import javax.swing.*;
import javax.swing.table.*;
import java.awt.*;
import java.awt.Color;
import java.awt.Font;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.sql.*;
import java.util.*;
import java.util.List;
import utils.ImageUtil;
import excelo.Classify;

import configs.MySQLConnect;

import excelo.DataBaseManager;

public class AppUI extends JFrame {
    private JTable table;
    private String importedFileName;
    private DefaultTableModel tableModel;
    private JButton uploadButton, insertButton, selectButton, analyzeButton,classifyButton;
    private JLabel headerLabel;
    private JLabel LogoTitle;
    private JPanel tablePanel, sidebarPanel;
    private JTextField searchField;
    private JProgressBar progressBar;

    // Variables to store selected row and column range
    private int selectedRowStart = -1, selectedRowEnd = -1, selectedColumnStart = -1, selectedColumnEnd = -1;

    public AppUI() {
        // Set FlatLaf Look and Feel
        try {
            UIManager.setLookAndFeel(new FlatLightLaf());
        } catch (Exception e) {
            e.printStackTrace();
        }

        setTitle("Excel File Editor");
        setSize(1000, 600);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLocationRelativeTo(null);

        // Define New Color Palette
        Color backgroundColor = new Color(240, 248, 255);   // Light pastel blue background
        Color sidebarColor = new Color(25, 25, 112);         // Midnight Blue for sidebar
        Color buttonColor = new Color(70, 130, 180);         // Steel Blue buttons
        Color tableRowColor = new Color(255, 255, 255);      // White for table rows
        Color tableSelectionColor = new Color(100, 149, 237);// Cornflower Blue for selection

        setLayout(new BorderLayout());
        getContentPane().setBackground(backgroundColor);

        // ===== Sidebar (WEST) =====
        sidebarPanel = new JPanel();
        sidebarPanel.setLayout(new BoxLayout(sidebarPanel, BoxLayout.Y_AXIS));
        sidebarPanel.setBackground(sidebarColor);
        sidebarPanel.setBorder(BorderFactory.createEmptyBorder(30, 10, 30, 10));

        uploadButton = new JButton("Upload Excel File");
        insertButton = new JButton("Save This Excel File");
        selectButton = new JButton("Create a View");
        analyzeButton = new JButton("Analyze Sheet");
        classifyButton = new JButton("Classify Sheet");

        styleButton(uploadButton, buttonColor);
        styleButton(insertButton, buttonColor);
        styleButton(selectButton, buttonColor);
        styleButton(analyzeButton, buttonColor);
        styleButton(classifyButton, buttonColor);

        uploadButton.addActionListener(this::uploadExcel);
        insertButton.addActionListener(this::insertIntoDatabase);
        selectButton.addActionListener(this::selectRowAndColumn);
        analyzeButton.addActionListener(this::analyzeSheet);
        classifyButton.addActionListener(this::classifySheet);

        sidebarPanel.add(uploadButton);
        sidebarPanel.add(Box.createRigidArea(new Dimension(0, 20)));
        sidebarPanel.add(insertButton);
        sidebarPanel.add(Box.createRigidArea(new Dimension(0, 20)));
        sidebarPanel.add(selectButton);
        sidebarPanel.add(Box.createRigidArea(new Dimension(0, 20)));
        sidebarPanel.add(analyzeButton);
        sidebarPanel.add(Box.createRigidArea(new Dimension(0, 20)));
        sidebarPanel.add(classifyButton);

        add(sidebarPanel, BorderLayout.WEST);

        // ===== Table Setup =====
        tableModel = new DefaultTableModel();
        table = new JTable(tableModel) {
            public Component prepareRenderer(TableCellRenderer renderer, int row, int column) {
                Component c = super.prepareRenderer(renderer, row, column);
                if (!isRowSelected(row)) {
                    c.setBackground(tableRowColor);
                } else {
                    c.setBackground(tableSelectionColor);
                }
                return c;
            }
        };



        JTableHeader header = table.getTableHeader();
        header.setFont(new Font("Helvetica Neue", Font.BOLD, 15));
        header.setBackground(new Color(70, 130, 180));
        header.setForeground(Color.WHITE);
        header.setOpaque(true);

        table.setFont(new Font("Helvetica Neue", Font.PLAIN, 14));
        table.setRowHeight(35);
        table.setSelectionBackground(tableSelectionColor);
        table.setSelectionForeground(Color.WHITE);
        table.setGridColor(new Color(200, 200, 200));
        table.setShowHorizontalLines(false);
        table.setShowVerticalLines(false);
        table.setIntercellSpacing(new Dimension(0, 0));

        JScrollPane scrollPane = new JScrollPane(table);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());

        tablePanel = new JPanel(new BorderLayout());
        tablePanel.setBackground(backgroundColor);
        tablePanel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
        tablePanel.add(scrollPane, BorderLayout.CENTER);

        // ===== Header Panel (Image + Text Side-by-Side) =====
        JPanel headerPanel = new JPanel(new BorderLayout());
        headerPanel.setBackground(backgroundColor);
        headerPanel.setBorder(BorderFactory.createEmptyBorder(10, 20, 10, 20));

        // Logo
        // Load and scale the image with high quality
        ImageIcon scaledIcon = ImageUtil.getHighQualityScaledIcon("src/images/Excelo.png", 60, 60);
        JLabel logoLabel = new JLabel(scaledIcon);
        logoLabel.setPreferredSize(new Dimension(90, 90));
        headerPanel.add(logoLabel, BorderLayout.WEST);

        // Text
        JPanel textPanel = new JPanel();
        textPanel.setLayout(new BoxLayout(textPanel, BoxLayout.Y_AXIS));
        textPanel.setBackground(backgroundColor);

        LogoTitle = new JLabel(
                "<html>" +
                        "<span style='font-family:Helvetica Neue, Helvetica, Arial, sans-serif; font-size:28px; font-weight:900;'>Excelo</span> " +
                        "<span style='font-size:16px;'><i>- Fast! , Quick! , Easy!</i></span>" +
                        "</html>"
        );
        LogoTitle.setFont(new Font("Helvetica Neue", Font.BOLD, 16));
        LogoTitle.setForeground(new Color(70, 130, 180));

        headerLabel = new JLabel("Import Excel File - Perform operation you want on it");
        headerLabel.setFont(new Font("Helvetica Neue", Font.BOLD, 16));
        headerLabel.setForeground(new Color(70, 130, 180));

        textPanel.add(LogoTitle);
        textPanel.add(Box.createRigidArea(new Dimension(0, 5)));
        textPanel.add(headerLabel);

        headerPanel.add(textPanel, BorderLayout.CENTER);

        // Main Panel
        JPanel mainContent = new JPanel(new BorderLayout());
        mainContent.setBackground(backgroundColor);
        mainContent.add(headerPanel, BorderLayout.NORTH);
        mainContent.add(tablePanel, BorderLayout.CENTER);

        add(mainContent, BorderLayout.CENTER);

        LogoTitle.setVisible(true);
        headerLabel.setVisible(true);
        tablePanel.setVisible(false);

        progressBar = new JProgressBar();
        progressBar.setIndeterminate(true);
        progressBar.setVisible(false);
        add(progressBar, BorderLayout.SOUTH);
    }

    private void styleButton(JButton button, Color color) {
        button.setBackground(color);
        button.setForeground(Color.WHITE);
        button.setFont(new Font("Helvetica Neue", Font.BOLD, 16));
        button.setPreferredSize(new Dimension(200, 50));
        button.setFocusPainted(false);
        button.setOpaque(true);
        button.setBorderPainted(false);
        button.setAlignmentX(Component.CENTER_ALIGNMENT);
        button.setCursor(new Cursor(Cursor.HAND_CURSOR));
    }

    private void uploadExcel(ActionEvent e) {
        JFileChooser fileChooser = new JFileChooser();
        int option = fileChooser.showOpenDialog(this);

        if (option == JFileChooser.APPROVE_OPTION) {
            File file = fileChooser.getSelectedFile();
            importedFileName = file.getName();
            readExcelFile(file);

//            headerLabel.setVisible(true);
            tablePanel.setVisible(true);
        }
    }

    public ImageIcon getHighQualityScaledIcon(String path, int width, int height) {
        ImageIcon icon = new ImageIcon(path);
        Image img = icon.getImage();

        BufferedImage resized = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = resized.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.drawImage(img, 0, 0, width, height, null);
        g2d.dispose();

        return new ImageIcon(resized);
    }


    private void readExcelFile(File file) {
        try (FileInputStream fis = new FileInputStream(file);
             Workbook workbook = new XSSFWorkbook(fis)) {

            Sheet sheet = workbook.getSheetAt(0);
            tableModel.setRowCount(0);
            tableModel.setColumnCount(0);

            boolean isFirstRow = true;
            for (Row row : sheet) {
                if (isFirstRow) {
                    for (Cell cell : row) {
                        tableModel.addColumn(cell.getStringCellValue());
                    }
                    isFirstRow = false;
                } else {
                    Object[] rowData = new Object[row.getLastCellNum()];
                    for (int i = 0; i < row.getLastCellNum(); i++) {
                        Cell cell = row.getCell(i, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
                        rowData[i] = getCellValue(cell);
                    }
                    tableModel.addRow(rowData);
                }
            }

        } catch (Exception ex) {
            ex.printStackTrace();
            JOptionPane.showMessageDialog(this, "Error reading Excel file: " + ex.getMessage());
        }
    }

    private Object getCellValue(Cell cell) {
        switch (cell.getCellType()) {
            case STRING: return cell.getStringCellValue();
            case NUMERIC: return cell.getNumericCellValue();
            case BOOLEAN: return cell.getBooleanCellValue();
            default: return "";
        }
    }

    private void insertIntoDatabase(ActionEvent e) {
        try (Connection conn = MySQLConnect.getConnection()) {
            if (importedFileName == null || importedFileName.isEmpty()) {
                JOptionPane.showMessageDialog(this, "No file imported. Please import an Excel file first.");
                return;
            }

            String baseName = importedFileName.replaceAll("\\.[^.]*$", "");
            String cleanTableName = baseName.replaceAll("[^a-zA-Z0-9_]", "_");

            String tableName = cleanTableName;
            int suffix = 1;
            while (DataBaseManager.tableExists(conn, tableName)) {
                tableName = cleanTableName + "_" + suffix;
                suffix++;
            }

            List<String> columnNames = new ArrayList<>();
            for (int i = 0; i < tableModel.getColumnCount(); i++) {
                columnNames.add(tableModel.getColumnName(i));
            }

            DataBaseManager.createTable(conn, tableName, columnNames);

            List<List<Object>> data = new ArrayList<>();
            for (int row = 0; row < tableModel.getRowCount(); row++) {
                List<Object> rowData = new ArrayList<>();
                for (int col = 0; col < tableModel.getColumnCount(); col++) {
                    rowData.add(tableModel.getValueAt(row, col));
                }
                data.add(rowData);
            }

            DataBaseManager.insertIntoDatabase(conn, tableName, columnNames, data);

            JOptionPane.showMessageDialog(this,
                    "Data inserted successfully!\nTable Name: " + tableName +
                            "\nImported File: " + importedFileName);

        } catch (Exception ex) {
            ex.printStackTrace();
            JOptionPane.showMessageDialog(this, "Error inserting into database: " + ex.getMessage());
        }
    }

    // Keeps the function even though button is removed
    private void exportToExcel(ActionEvent e) {
        if (selectedRowStart == -1 || selectedColumnStart == -1) {
            JOptionPane.showMessageDialog(this, "No rows or columns selected for export.");
            return;
        }

        JFileChooser fileChooser = new JFileChooser();
        int option = fileChooser.showSaveDialog(this);

        if (option == JFileChooser.APPROVE_OPTION) {
            File file = fileChooser.getSelectedFile();
            if (!file.getName().endsWith(".xlsx")) {
                file = new File(file.getAbsolutePath() + ".xlsx");
            }

            try (FileOutputStream fos = new FileOutputStream(file);
                 Workbook workbook = new XSSFWorkbook()) {

                Sheet sheet = workbook.createSheet("Exported Data");

                Row headerRow = sheet.createRow(0);
                for (int j = selectedColumnStart; j <= selectedColumnEnd; j++) {
                    Cell cell = headerRow.createCell(j - selectedColumnStart);
                    cell.setCellValue(tableModel.getColumnName(j));
                }

                // 👉 Add Data Rows
                for (int i = selectedRowStart; i <= selectedRowEnd; i++) {
                    Row row = sheet.createRow(i - selectedRowStart + 1);  // +1 to account for header
                    for (int j = selectedColumnStart; j <= selectedColumnEnd; j++) {
                        Cell cell = row.createCell(j - selectedColumnStart);
                        Object value = tableModel.getValueAt(i, j);
                        cell.setCellValue(value != null ? value.toString() : "");
                    }
                }

                workbook.write(fos);
                JOptionPane.showMessageDialog(this, "Data exported successfully!");

            } catch (IOException ex) {
                ex.printStackTrace();
                JOptionPane.showMessageDialog(this, "Error exporting data: " + ex.getMessage());
            }
        }
    }

    public void loadDataFromDatabase(String tableName) {
        try (Connection conn = MySQLConnect.getConnection()) {
            Statement stmt = conn.createStatement();
            ResultSet rs = stmt.executeQuery("SELECT * FROM " + tableName);
            ResultSetMetaData metaData = rs.getMetaData();
            int columnCount = metaData.getColumnCount();

            // Clear the existing model
            tableModel.setRowCount(0);
            tableModel.setColumnCount(0);

            // Set column names dynamically
            for (int i = 1; i <= columnCount; i++) {
                tableModel.addColumn(metaData.getColumnName(i));
            }

            // Add rows dynamically
            while (rs.next()) {
                Object[] rowData = new Object[columnCount];
                for (int i = 1; i <= columnCount; i++) {
                    rowData[i - 1] = rs.getObject(i); // Add row data
                }
                tableModel.addRow(rowData);
                tablePanel.setVisible(true);
            }

        } catch (SQLException ex) {
            ex.printStackTrace();
            JOptionPane.showMessageDialog(this, "Error loading table content: " + ex.getMessage());
        }
    }

    private void selectRowAndColumn(ActionEvent e) {
        JPanel panel = new JPanel(new GridLayout(4, 2));
        JTextField rowStartField = new JTextField();
        JTextField rowEndField = new JTextField();
        JTextField colStartField = new JTextField();
        JTextField colEndField = new JTextField();

        panel.add(new JLabel("Start Row:"));
        panel.add(rowStartField);
        panel.add(new JLabel("End Row:"));
        panel.add(rowEndField);
        panel.add(new JLabel("Start Column:"));
        panel.add(colStartField);
        panel.add(new JLabel("End Column:"));
        panel.add(colEndField);

        int option = JOptionPane.showConfirmDialog(this, panel, "Select Rows & Columns", JOptionPane.OK_CANCEL_OPTION);

        if (option == JOptionPane.OK_OPTION) {
            try {
                selectedRowStart = Integer.parseInt(rowStartField.getText());
                selectedRowEnd = Integer.parseInt(rowEndField.getText());
                selectedColumnStart = Integer.parseInt(colStartField.getText());
                selectedColumnEnd = Integer.parseInt(colEndField.getText());

                exportToExcel(null);
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(this, "Please enter valid numbers.");
            }
        }
    }

    private void classifySheet(ActionEvent e) {
         // Pass model to Classify window
        new Classify(importedFileName,importedFileName).setVisible(true);;
    }

    private void analyzeSheet(ActionEvent e) {
        if (tableModel.getRowCount() == 0 || tableModel.getColumnCount() < 2) {
            JOptionPane.showMessageDialog(this, "Please upload a sheet with at least two columns.");
            return;
        }

        // Create a frame for graph selection and display
        JFrame graphFrame = new JFrame("Excelo - Graph Analyzer");
        graphFrame.setSize(900, 700);
        graphFrame.setLocationRelativeTo(null);
        graphFrame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);

        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(new Color(245, 250, 255));
        panel.setBorder(BorderFactory.createEmptyBorder(20, 40, 20, 40));

        JLabel title = new JLabel("📊 Select Columns to Generate Graph");
        title.setFont(new Font("Helvetica Neue", Font.BOLD, 24));
        title.setForeground(new Color(25, 25, 112));
        title.setAlignmentX(Component.CENTER_ALIGNMENT);
        panel.add(title);
        panel.add(Box.createRigidArea(new Dimension(0, 20)));

        // Dropdowns to select X (Category) and Y (Value)
        JComboBox<String> xCombo = new JComboBox<>();
        JComboBox<String> yCombo = new JComboBox<>();

        for (int i = 0; i < tableModel.getColumnCount(); i++) {
            String columnName = tableModel.getColumnName(i);
            xCombo.addItem(columnName);
            yCombo.addItem(columnName);
        }

        JPanel dropdownPanel = new JPanel(new GridLayout(2, 2, 10, 10));
        dropdownPanel.setBackground(new Color(245, 250, 255));
        dropdownPanel.add(new JLabel("X-Axis (Category):"));
        dropdownPanel.add(xCombo);
        dropdownPanel.add(new JLabel("Y-Axis (Value):"));
        dropdownPanel.add(yCombo);
        panel.add(dropdownPanel);
        panel.add(Box.createRigidArea(new Dimension(0, 20)));

        JButton generateBtn = new JButton("Generate Chart");
        generateBtn.setFont(new Font("Helvetica Neue", Font.BOLD, 18));
        generateBtn.setBackground(new Color(70, 130, 180));
        generateBtn.setForeground(Color.WHITE);
        generateBtn.setFocusPainted(false);
        generateBtn.setAlignmentX(Component.CENTER_ALIGNMENT);
        panel.add(generateBtn);
        panel.add(Box.createRigidArea(new Dimension(0, 20)));

        JPanel chartPanelContainer = new JPanel(new BorderLayout());
        chartPanelContainer.setBackground(new Color(245, 250, 255));
        panel.add(chartPanelContainer);

        generateBtn.addActionListener(evt -> {
            String xCol = (String) xCombo.getSelectedItem();
            String yCol = (String) yCombo.getSelectedItem();
            if (xCol == null || yCol == null || xCol.equals(yCol)) {
                JOptionPane.showMessageDialog(graphFrame, "Please select two different columns.");
                return;
            }

            // Build dataset
            DefaultCategoryDataset dataset = new DefaultCategoryDataset();
            int xIndex = tableModel.findColumn(xCol);
            int yIndex = tableModel.findColumn(yCol);

            for (int row = 0; row < tableModel.getRowCount(); row++) {
                Object category = tableModel.getValueAt(row, xIndex);
                Object value = tableModel.getValueAt(row, yIndex);
                if (category != null && value instanceof Number) {
                    dataset.addValue(((Number) value).doubleValue(), yCol, category.toString());
                }
            }

            JFreeChart chart = ChartFactory.createBarChart(
                    yCol + " vs " + xCol,
                    xCol,
                    yCol,
                    dataset
            );

            chart.getTitle().setPaint(new Color(25, 25, 112));
            chart.setBackgroundPaint(Color.white);

            chartPanelContainer.removeAll();
            chartPanelContainer.add(new ChartPanel(chart), BorderLayout.CENTER);
            chartPanelContainer.revalidate();
            chartPanelContainer.repaint();
        });

        graphFrame.setContentPane(panel);
        graphFrame.setVisible(true);
    }




    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new AppUI().setVisible(true));
    }
}
