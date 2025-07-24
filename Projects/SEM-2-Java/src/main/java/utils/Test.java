package utils;

import javax.swing.*;
import java.awt.event.*;

public class Test {
    public static void main(String[] args) {
        JFrame frame = new JFrame("IEEE Registration");
        frame.setSize(350, 300);
        frame.setLayout(null);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        // Name
        JLabel nameLabel = new JLabel("Name:");
        nameLabel.setBounds(30, 30, 80, 25);
        frame.add(nameLabel);

        JTextField nameField = new JTextField();
        nameField.setBounds(120, 30, 180, 25);
        frame.add(nameField);

        // Email
        JLabel emailLabel = new JLabel("Email:");
        emailLabel.setBounds(30, 70, 80, 25);
        frame.add(emailLabel);

        JTextField emailField = new JTextField();
        emailField.setBounds(120, 70, 180, 25);
        frame.add(emailField);

        // College
        JLabel collegeLabel = new JLabel("College:");
        collegeLabel.setBounds(30, 110, 80, 25);
        frame.add(collegeLabel);

        JTextField collegeField = new JTextField();
        collegeField.setBounds(120, 110, 180, 25);
        frame.add(collegeField);

        // Membership Type
        JLabel typeLabel = new JLabel("Type:");
        typeLabel.setBounds(30, 150, 80, 25);
        frame.add(typeLabel);

        String[] types = {"Student", "Professional", "Associate"};
        JComboBox<String> typeBox = new JComboBox<>(types);
        typeBox.setBounds(120, 150, 180, 25);
        frame.add(typeBox);

        // Button
        JButton submitButton = new JButton("Submit");
        submitButton.setBounds(120, 190, 100, 30);
        frame.add(submitButton);

        // Result label
        JLabel resultLabel = new JLabel("");
        resultLabel.setBounds(30, 230, 300, 25);
        frame.add(resultLabel);

        // Button action
        submitButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                String name = nameField.getText().trim();
                String email = emailField.getText().trim();
                String college = collegeField.getText().trim();
                String type = (String) typeBox.getSelectedItem();

                if (name.isEmpty() || email.isEmpty() || college.isEmpty()) {
                    resultLabel.setText("Please fill all fields.");
                } else {
                    resultLabel.setText("Registered: " + name + " (" + type + ")");
                }
            }
        });

        frame.setVisible(true);
    }
}