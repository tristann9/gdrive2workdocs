package ie.binary.gdrive2workdocs;

import java.io.IOException;

public class Main {

    public static void main(String args[]) throws IOException {

        if (args.length == 1) {
            System.setProperty("SETTINGS_FILE", args[0]);
        }

        //System.setProperty("SETTINGS_FILE", "~/gdrive2workdocs/build/data/settings.yaml");


        new Main().start();
    }

    public void start() throws IOException {
        for (String name : DataUtils.getGdriveNames()) {
            new GDriveHelper(name).start();
        }
    }
}