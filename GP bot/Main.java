
public class Main {
    public static void main(String[] args) {
        GP gp = new GP();

        long startTime = System.currentTimeMillis();
        gp.run();
        long runtimeMs = System.currentTimeMillis() - startTime;
        
        System.out.printf("Runtime: %d ms%n", runtimeMs);
    }
}
