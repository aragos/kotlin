
public class Main {
    public static String STATIC_CONST = "String const";
    public Object constValue = new Object();

    public static void main(String[] args) {
        System.out.println();
        Main main = new Main();
        main.privateMethod(STATIC_CONST);
        main.publicMethod(main.constValue);
    }

    private String privateMethod(String arg) {
        System.out.println(arg);
        return "new String";
    }

    public Object publicMethod(Object arg) {
        System.out.println(arg.hashCode());
        return new Object();
    }
}
