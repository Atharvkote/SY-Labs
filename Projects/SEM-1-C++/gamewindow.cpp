
#include "gamewindow.h"
#include <QMessageBox>
#include <QFont>
#include <QVBoxLayout>
#include <QSpacerItem>
#include <QHBoxLayout>

GameWindow::GameWindow(QWidget *parent)
    : QWidget(parent), playerTurn(true) {
    setWindowTitle("Tic-Tac-Toe");
    setFixedSize(400, 400); // Set the window size
    QMargins margins(85,0,0,0);
    // Outer layout to center the grid
    QVBoxLayout *outerLayout = new QVBoxLayout(this);
    outerLayout->setAlignment(Qt::AlignCenter); // Align center vertically
    outerLayout->setContentsMargins(margins);
    // Horizontal layout to center the grid horizontally
    QHBoxLayout *hBoxLayout = new QHBoxLayout();
    hBoxLayout->setAlignment(Qt::AlignCenter);

    // Create grid layout
    gridLayout = new QGridLayout();
    gridLayout->setSpacing(10); // Add spacing between buttons

    // Create 3x3 grid of buttons
    for (int i = 0; i < 3; ++i) {
        for (int j = 0; j < 3; ++j) {
            buttons[i][j] = new QPushButton("");
            buttons[i][j]->setFixedSize(100, 100); // Button size
            buttons[i][j]->setFont(QFont("Arial", 24, QFont::Bold));
            gridLayout->addWidget(buttons[i][j], i, j);
            buttons[i][j]->setCursor(Qt::PointingHandCursor); // Set hand cursor
            connect(buttons[i][j], &QPushButton::clicked, this, &GameWindow::handleButtonClick);
        }
    }

    // Add the grid layout to the horizontal layout
    hBoxLayout->addLayout(gridLayout);

    // Add the horizontal layout to the vertical layout
    outerLayout->addLayout(hBoxLayout);

    setLayout(outerLayout); // Set the final layout for the window
}

GameWindow::~GameWindow() {}

void GameWindow::handleButtonClick() {
    QPushButton *button = qobject_cast<QPushButton *>(sender());
    if (button->text() != "") return; // Ignore if button is already clicked

    button->setText(playerTurn ? "X" : "O");
    playerTurn = !playerTurn;
    checkWin();
}

void GameWindow::checkWin() {
    // Check rows, columns, and diagonals for a win
    for (int i = 0; i < 3; ++i) {
        if (buttons[i][0]->text() == buttons[i][1]->text() && buttons[i][1]->text() == buttons[i][2]->text() && buttons[i][0]->text() != "") {
            QMessageBox::information(this, "Game Over", QString("Player %1 wins!").arg(buttons[i][0]->text()));
            resetBoard();
            return;
        }
        if (buttons[0][i]->text() == buttons[1][i]->text() && buttons[1][i]->text() == buttons[2][i]->text() && buttons[0][i]->text() != "") {
            QMessageBox::information(this, "Game Over", QString("Player %1 wins!").arg(buttons[0][i]->text()));
            resetBoard();
            return;
        }
    }
    if (buttons[0][0]->text() == buttons[1][1]->text() && buttons[1][1]->text() == buttons[2][2]->text() && buttons[0][0]->text() != "") {
        QMessageBox::information(this, "Game Over", QString("Player %1 wins!").arg(buttons[0][0]->text()));
        resetBoard();
        return;
    }
    if (buttons[0][2]->text() == buttons[1][1]->text() && buttons[1][1]->text() == buttons[2][0]->text() && buttons[0][2]->text() != "") {
        QMessageBox::information(this, "Game Over", QString("Player %1 wins!").arg(buttons[0][2]->text()));
        resetBoard();
        return;
    }

    // Check for a draw
    bool draw = true;
    for (int i = 0; i < 3; ++i) {
        for (int j = 0; j < 3; ++j) {
            if (buttons[i][j]->text() == "") draw = false;
        }
    }
    if (draw) {
        QMessageBox::information(this, "Game Over", "It's a draw!");
        resetBoard();
    }
}

void GameWindow::resetBoard() {
    for (int i = 0; i < 3; ++i) {
        for (int j = 0; j < 3; ++j) {
            buttons[i][j]->setText("");
        }
    }
    playerTurn = true; // Reset player turn to "X"
}
