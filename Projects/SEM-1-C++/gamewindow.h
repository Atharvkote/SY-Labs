#ifndef GAMEWINDOW_H
#define GAMEWINDOW_H

#include <QWidget>
#include <QPushButton>
#include <QGridLayout>

class GameWindow : public QWidget {
    Q_OBJECT

public:
    GameWindow(QWidget *parent = nullptr);
    ~GameWindow();

private slots:
    void handleButtonClick();

private:
    QGridLayout *gridLayout;
    QPushButton *buttons[3][3];
    bool playerTurn;
    void checkWin();
    void resetBoard();
};

#endif // GAMEWINDOW_H
