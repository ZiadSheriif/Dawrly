package SearchPackage;

import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.*;
import java.util.*;
import java.util.List;

public class Crawler implements Runnable {
    //will be edited later to 5000
    private static final int MAX_PAGES_TO_SEARCH = 10;
    private int NofVisitedPages;
    //used to save the special word of any page visited before
    private Set<String> pagesVisited = new HashSet<String>();
    //links of pages that will be visited next
    private List<String> pagesToVisit = new LinkedList<String>();
    private MongoDB database;
    private RobotCheck robotObject = new RobotCheck();
    private Object o1,o2,o3,o4;

    //methods
    public Crawler() {
        database = new MongoDB();
        database.ConnectToDataBase();
        this.NofVisitedPages = 0;
        o1 = new Object();
        o2 = new Object();
        o3 = new Object();
        o4 = new Object();
    }

    @Override
    public void run() {
        while (true) {
            String pageUrl;
            synchronized (o1) {
                //get the first link of the array
                if (pagesToVisit.size() > 0) {
                    pageUrl = pagesToVisit.remove(0);
                    database.UpdatePagesToVisit(pageUrl);
                } else break;
            }

            System.out.println("Current link: " + pageUrl + " To Thread " + Thread.currentThread().getName());

            //connect to the page
            Connection connection;
            Document htmlDocument = null;
            try {
                connection = Jsoup.connect(pageUrl);
                htmlDocument = connection.get();
            } catch (IOException e) {
                e.printStackTrace();
                continue;
            }

            //check the robot file and don't continue if the link is forbidden
            if (!robotObject.robotAllowed(pageUrl))
                continue;

            //collect the special word or normalize the url
            String SpecialWord = CollectSpecialWord(htmlDocument);
            //Check if that page was visited before
            if (!pagesVisited.contains(SpecialWord)) {
                synchronized (o2) {
                    pagesVisited.add(SpecialWord);
                    database.UpdatePagesVisited(SpecialWord);
                }

                //Download the page for the indexer
                //And get all the links that can be visited later from it
                DownloadHTML(htmlDocument);
                List<String> Links = getLinks(htmlDocument);

                synchronized (o3) {
                    pagesToVisit.addAll(Links);
                    database.UpdatePagesToVisit(Links);
                }

                synchronized (o4) {
                    NofVisitedPages++;
                    database.UpdateNofVisitedPages(NofVisitedPages);
                    System.out.println(NofVisitedPages);

                    if (NofVisitedPages >= MAX_PAGES_TO_SEARCH)
                        break;
                }
            }
        }

    }

    public void Crawl() {

        if (database.CheckState().equals("Interrupted")) {
            NofVisitedPages = (int) database.GetSavedLinks(pagesToVisit, pagesVisited);
        } else {
            pagesToVisit = GetLinksFromSeedFile();
        }
        if (pagesToVisit.size() == 0)
            pagesToVisit = GetLinksFromSeedFile();

        database.ChangeState("Interrupted");

        Scanner sc= new Scanner(System.in);
        System.out.print("Enter the number of Thread: ");
        int num = sc.nextInt();

        //Create the number of wanted Threads
        Thread[] threads = new Thread[num];
        for (int i = 0; i < num; i++)
        {
            threads[i] = new Thread(this);
            threads[i].setName(Integer.toString(i));
            threads[i].start();
        }

        //Join all the Threads
        for (int i = 0; i < num; i++) {
            try {
                threads[i].join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        database.ChangeState("Finished");
        System.out.println("Crawling Finished Successfully");
    }

    public List<String> GetLinksFromSeedFile() {
        List<String> Links = new ArrayList<>();
        try {
            File myObj = new File("src\\SearchPackage\\Seeds.txt");
            Scanner myReader = new Scanner(myObj);
            while (myReader.hasNextLine()) {
                String data = myReader.nextLine();
                Links.add(data);
            }
            myReader.close();
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }
        return Links;
    }

    public List<String> getLinks(Document htmlDocument) {
        List<String> links = new LinkedList<String>();
        for (Element link : htmlDocument.select("a[href]")) {
            String slink = link.absUrl("href");
            if (!slink.equals("javascript:void(0)") && !slink.equals("#"))
                links.add(slink);
        }
        return links;
    }

    //may need to send the document when we implement the class using threads
    public void DownloadHTML(Document htmlDocument) {
        final String path = "downloads\\";
        String name = htmlDocument.baseUri();
        if (name.length() > 10) {
            name = name.substring(0, 10);
        }

        try {
            BufferedWriter writer = new BufferedWriter(new FileWriter(path + name + ".html"));
            writer.write(htmlDocument.toString());
            writer.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public String CollectSpecialWord(Document htmlDocument) {
        StringBuilder Collector = new StringBuilder();

        //collect the title of the page
        String title = htmlDocument.title().replaceAll(" ", "");
        Collector.append(title);

        //collect the first char of some words in the body
        String body = htmlDocument.body().text();
        String[] bodyWords = body.split(" ");
        for (int i = 0; i < bodyWords.length; i += 10)
            if (!bodyWords[i].isEmpty())
                Collector.append(bodyWords[i].charAt(0));

        return Collector.toString();
    }

    public static void main(String[] arg) {
        Crawler c = new Crawler();
        c.Crawl();
    }
}
