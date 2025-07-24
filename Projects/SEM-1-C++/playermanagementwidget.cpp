#include "PlayerManagementWidget.h"
#include "ui_PlayerManagementWidget.h"
#include <QVBoxLayout>
#include <QMessageBox>

PlayerManagementWidget::PlayerManagementWidget(QWidget *parent)
    : QWidget(parent), ui(new Ui::PlayerManagementWidget) // Correct initialization of ui
{
    ui->setupUi(this);

    // Disable the start button initially
    ui->startGameButton->setEnabled(false);

    // Connect combo box signals to enable the start button when two players are selected
    connect(ui->player1ComboBox, &QComboBox::currentTextChanged, this, &PlayerManagementWidget::updateStartGameButtonState);
    connect(ui->player2ComboBox, &QComboBox::currentTextChanged, this, &PlayerManagementWidget::updateStartGameButtonState);

    // Connect start game button to the start game slot
    connect(ui->startGameButton, &QPushButton::clicked, this, &PlayerManagementWidget::startGame);
}

PlayerManagementWidget::~PlayerManagementWidget()
{
    delete ui; // Proper cleanup of ui
}

void PlayerManagementWidget::updateStartGameButtonState()
{
    // Enable the button only if both combo boxes have a valid selection
    QString player1 = ui->player1ComboBox->currentText();
    QString player2 = ui->player2ComboBox->currentText();

    bool canStart = !player1.isEmpty() && !player2.isEmpty() && (player1 != player2);
    ui->startGameButton->setEnabled(canStart);
}

void PlayerManagementWidget::startGame()
{
    QString player1 = ui->player1ComboBox->currentText();
    QString player2 = ui->player2ComboBox->currentText();

    // Validate again (extra safety, though button logic prevents this case)
    if (player1.isEmpty() || player2.isEmpty()) {
        QMessageBox::warning(this, "Error", "Both players must be selected!");
        return;
    }

    if (player1 == player2) {
        QMessageBox::warning(this, "Error", "Players cannot be the same!");
        return;
    }

    // Emit the signal to start the game
    emit gameStarted(player1, player2);
}
