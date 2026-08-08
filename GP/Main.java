
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Scanner;

public class Main {
    public static void main(String[] args) {// train using n days' values at the same day and time, or train using m
                                            // previous days' values
        GP gp = new GP();

        long startTime = System.currentTimeMillis();
        gp.run();
        gp.runTest();
        long runtimeMs = System.currentTimeMillis() - startTime;
        // //System.out.println("Runtime: " + runtimeMs + " ms");
        gp.printFinalResults(runtimeMs);



        ////Enable me to try out the best tree 
        // Scanner scanner = new Scanner(System.in);
        // while (true) {
        //     System.out.print("Do you want to predict a value? (y/n): ");
        //     String predictChoice = scanner.nextLine().trim().toLowerCase();

        //     if (predictChoice.equals("n")) {
        //         break;
        //     }

        //     if (!predictChoice.equals("y")) {
        //         System.out.println("Invalid input. Please enter 'y' or 'n'.");
        //         continue;
        //     }

        //     LocalDateTime userDateTime = promptForDateTimeInput(scanner);
        //     String inputDateTime = userDateTime.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"));
        //     gp.predictForDateTime(inputDateTime);

        //     while (true) {
        //         System.out.print("Do you want to try again? (y/n): ");
        //         String retryChoice = scanner.nextLine().trim().toLowerCase();
        //         if (retryChoice.equals("y")) {
        //             break;
        //         }
        //         if (retryChoice.equals("n")) {
        //             return;
        //         }
        //         System.out.println("Invalid input. Please enter 'y' or 'n'.");
        //     }
        // }
    }

    public static LocalDateTime promptForDateTimeInput(Scanner scanner) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd/MM/uuuu HH:mm")
                .withResolverStyle(ResolverStyle.STRICT);

        while (true) {
            System.out.print("Enter date and time (dd/MM/yyyy HH:mm): ");
            String input = scanner.nextLine();

            try {
                return LocalDateTime.parse(input, formatter);
            } catch (DateTimeParseException e) {
                System.out.println("Invalid format. Please use exactly dd/MM/yyyy HH:mm (example: 12/01/2015 09:45).");
            }
        }
    }

}
