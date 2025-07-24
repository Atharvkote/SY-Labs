#include "mainwindow.h"
#include "ui_mainwindow.h"
#include "gamewindow.h"



MainWindow::MainWindow(QWidget *parent)
    : QMainWindow(parent), ui(new Ui::MainWindow) {
    ui->setupUi(this);
    setFixedSize(500, 600);

    // Initialize GameWindow and LeaderboardWindow
    gameWindow = new GameWindow(this);


    // Add GameWindow to the layout (or QStackedWidget)
    ui->stackedWidget->addWidget(gameWindow);


    // Connect buttons to switch between views
    connect(ui->startGameButton, &QPushButton::clicked, this, &MainWindow::startGame);
     connect(ui->closeButton, &QPushButton::clicked, this, &MainWindow::closeButton);

}

MainWindow::~MainWindow() {
    delete ui;
}

void MainWindow::startGame() {
    // Switch to the GameWindow
    ui->stackedWidget->setCurrentWidget(gameWindow);
}

void MainWindow::closeButton() {
    QApplication::quit(); // Or close() if it's a QMainWindow or QDialog.
}

