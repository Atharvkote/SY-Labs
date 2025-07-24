
#ifndef MAINWINDOW_H
#define MAINWINDOW_H

#include <QMainWindow>
#include "gamewindow.h"


namespace Ui {
class MainWindow;
}

class MainWindow : public QMainWindow {
    Q_OBJECT

public:
    explicit MainWindow(QWidget *parent = nullptr);
    ~MainWindow();

private slots:
    void startGame();        // Slot for handling the "Start Game" button click
    void closeButton();

private:
    Ui::MainWindow *ui;

    // Declare pointers to other windows
    GameWindow *gameWindow;
};

#endif // MAINWINDOW_H
