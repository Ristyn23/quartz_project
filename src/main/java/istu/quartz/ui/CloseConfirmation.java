package istu.quartz.ui;

import javax.swing.JOptionPane;
import java.awt.Component;

final class CloseConfirmation {
    private CloseConfirmation() { }

    static boolean confirm(Component owner, boolean working) {
        String message = working ? "Прервать текущую операцию и закрыть это окно?" : "Закрыть это окно?";
        Object[] options = {"Закрыть", "Отмена"};
        int answer = JOptionPane.showOptionDialog(owner, message, "Закрытие Quartz",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE, null, options, options[1]);
        return answer == 0;
    }
}
