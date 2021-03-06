
import java.util.*;
import java.io.*;

// mongo libraries
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;

import org.tartarus.snowball.ext.PorterStemmer;

import static com.mongodb.client.model.Filters.eq;

import org.bson.Document;

import org.bson.conversions.Bson;
import org.json.simple.JSONObject;

import org.jsoup.Jsoup;

import java.util.regex.Pattern;
import java.util.regex.Matcher;


public class Indexer extends ProcessString implements Runnable {
    private static int threadNumber;
    private static String[] fileNamesList;
    private static String folderRootPath;
    private static HashMap<String, HashMap<String, Pair<Integer, Integer, Double, Integer, Integer, Double>>> invertedIndex;
    // HashMap<fileName,All words in the file after processing>
    // This map helps in phrase searching
    private static HashMap<String, List<String>> processedFiles;
    private static HashMap<String, Double> tagsOfHtml;
    private static HashMap<String, HashMap<String, Double>> scoreOfWords;
    private static HashMap<String, HashMap<String, List<Integer>>> indicesOfWord;

    private static int document_numbers;
    // TODO: Synchronization of Threads to avoid Concurrency Exception

    public void startIndexing() throws InterruptedException {

        // Read threads number
        System.out.print("Enter number of threads: ");

        // Enter data using BufferReader
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(System.in));

        // Reading data using readLine
        try {
            threadNumber = Integer.parseInt(reader.readLine());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        System.out.println();

        long startTime = System.currentTimeMillis();

        invertedIndex = new HashMap<>();
        indicesOfWord = new HashMap<>();
        scoreOfWords = new HashMap<>();
        List<JSONObject> invertedIndexJSON;

        // read stop words and fill score of tags
        try {
            readStopWords();
            fillScoresOfTags();
        } catch (IOException e) {
            e.printStackTrace();
        }
        // creates a file object
        File file = new File("downloads");
        folderRootPath = "downloads//";
        // returns an array of all files
        fileNamesList = file.list();

        // document number
        document_numbers = fileNamesList.length;

        Thread[] threads = new Thread[threadNumber];
        for (int i = 0; i < threadNumber; i++) {
            threads[i] = new Thread(new Indexer());
            threads[i].setName((new Integer(i + 1)).toString());
        }
        for (int i = 0; i < threadNumber; i++) {
            threads[i].start();
        }
        for (int i = 0; i < threadNumber; i++) {
            threads[i].join();
        }

        // 8- calculateTF_IDF
        calculateTF_IDF();

        // 9- converted the inverted index into json format
        invertedIndexJSON = convertInvertedIndexToJSON(invertedIndex);
        long endTime_before_upload = System.currentTimeMillis();

        System.out.printf("Indexer has taken without uploading to database: %d seconds\n", (endTime_before_upload - startTime) / 1000);
        // 10- Upload to database
        System.out.println("Start uploading to database");
        uploadToDB(invertedIndexJSON);
        System.out.println("Indexer has finished");
        long endTime_after_upload = System.currentTimeMillis();
        System.out.printf("Indexer has taken with uploading to database: %d seconds\n", (endTime_after_upload - startTime) / 1000);
    }

    // 30
    // 0*6 => 1*6 0
    // 1*6 => 2*6
    // 2*6 => 3*6
    @Override
    public void run() {
        int start = (Integer.parseInt(Thread.currentThread().getName()) - 1) * (int) Math.ceil(fileNamesList.length / (double) threadNumber);
        int end = (Integer.parseInt(Thread.currentThread().getName())) * (int) Math.ceil(fileNamesList.length / (double) threadNumber);
        // iterate over files
        for (int i = start; i < Math.min(end, fileNamesList.length); i++) {
            String fileName = fileNamesList[i];
            String oldFileName = new String(fileName);
            // TODO: modify file name
            fileName = fileName.replace("`{}", "*");
            fileName = fileName.replace("}", "://");
            fileName = fileName.replace("{", "/");
            fileName = fileName.replace("`", "?");
            fileName = fileName.replace(".html", "");

            // 1- parse html
            StringBuilder noHTMLDoc = new StringBuilder("");
            StringBuilder originalDoc = new StringBuilder("");
            try {
                org.jsoup.nodes.Document html = parsingHTML(oldFileName, folderRootPath, noHTMLDoc, originalDoc);

                filterTags(html, fileName);
                createBodyFiles(html, fileNamesList[i]);
            } catch (IOException e) {
                e.printStackTrace();
            }
            // 2- split words
            List<String> words = splitWords(noHTMLDoc.toString());
            // 3-get indices of each word
            //getIndexOfWord(words, originalDoc, fileName); // TODO: Synchronized threads
            // 4-convert to lowercase
            convertToLower(words);
            // 5- remove stop words
            removeStopWords(words);
            // 6- stemming
            List<String> stemmedWords = stemming(words);
            // 7- fill other tags with score
            filOtherTags(stemmedWords, fileName);
            // 8- build processed words
            // buildProcessedFiles(fileName, stemmedWords);
            // 9- build inverted index
            buildInvertedIndex(stemmedWords, fileName, invertedIndex);
            System.out.printf("#%d Thread #%s processed file: %s\n", i, Thread.currentThread().getName(), fileName);
        }
    }

    private static org.jsoup.nodes.Document parsingHTML(String input, String path, StringBuilder HTML, StringBuilder Str) throws IOException {
        BufferedReader reader = new BufferedReader(new FileReader(path + input));
        String lines = "";
        while ((lines = reader.readLine()) != null) {
            Str.append(lines);
        }
        reader.close();
        org.jsoup.nodes.Document html = Jsoup.parse(Str.toString());
        Str.setLength(0);
        Str.append(html.body().text());
        HTML.append(html.title() + " " + html.body().text());
        return html;
    }

    private static synchronized void buildInvertedIndex(List<String> stemmedWords, String docName, HashMap<String, HashMap<String, Pair<Integer, Integer, Double, Integer, Integer, Double>>> invertedIndex) {
        for (int i = 0; i < stemmedWords.size(); i++) {
            String word = stemmedWords.get(i);
            // if word not exist then allocate a map for it
            if (!invertedIndex.containsKey(word)) {
                HashMap<String, Pair<Integer, Integer, Double, Integer, Integer, Double>> docsMapOfWord = new HashMap<String, Pair<Integer, Integer, Double, Integer, Integer, Double>>();
                invertedIndex.put(word, docsMapOfWord);
            }
            HashMap<String, Pair<Integer, Integer, Double, Integer, Integer, Double>> docsMapOfWord = invertedIndex.get(word);

            // if document not exist then allocate a pair for it
            if (!docsMapOfWord.containsKey(docName)) {
                Pair<Integer, Integer, Double, Integer, Integer, Double> TF_Size_pair = new Pair<Integer, Integer, Double, Integer, Integer, Double>(0, stemmedWords.size(), scoreOfWords.get(docName).get(word));
                docsMapOfWord.put(docName, TF_Size_pair);
                TF_Size_pair.index = new ArrayList<>();
                //TF_Size_pair.actualIndices = indicesOfWord.get(docName).get(word);
            }
            Pair<Integer, Integer, Double, Integer, Integer, Double> TF_Size_pair = docsMapOfWord.get(docName);
            TF_Size_pair.TF++;
            TF_Size_pair.index.add(i);
            TF_Size_pair.TF_IDF = 0.0;
        }
    }

    // TODO: insert the file and its processed words
    private static synchronized void buildProcessedFiles(String FileName, final List<String> stemmedWords) {
        processedFiles.put(FileName, stemmedWords);
    }

    private static List<JSONObject> convertInvertedIndexToJSON(HashMap<String, HashMap<String, Pair<Integer, Integer, Double, Integer, Integer, Double>>> invertedIndexP) {
        /*
        *
        {
            {
                word: word1
                docs:
                    [
                        {
                            docName:doc1
                            tf:10,
                            size:10
                        }
                    ]
            }
        }
        *
        * */
        List<JSONObject> listOfWordJSONS = new Vector<>();
        for (String word : invertedIndexP.keySet()) {
            JSONObject wordJSON = new JSONObject();
            wordJSON.put("word", word);
            List<JSONObject> documents = new Vector<>();
            for (String doc : invertedIndexP.get(word).keySet()) {
                JSONObject documentJSON = new JSONObject();
                documentJSON.put("document", doc);
                //documentJSON.put("tf", invertedIndexP.get(word).get(doc).TF);
                //documentJSON.put("size", invertedIndexP.get(word).get(doc).size);
                documentJSON.put("score", invertedIndexP.get(word).get(doc).score);
                documentJSON.put("index", invertedIndexP.get(word).get(doc).index);
                //documentJSON.put("actualIndices", invertedIndexP.get(word).get(doc).actualIndices);
                documentJSON.put("TF_IDF", invertedIndexP.get(word).get(doc).TF_IDF);
                documents.add(documentJSON);
            }
            wordJSON.put("documents", documents);
            listOfWordJSONS.add(wordJSON);
        }
        return listOfWordJSONS;
    }

    private static void uploadToDB(List<JSONObject> invertedIndexJSONParameter) {
        com.mongodb.MongoClient client = new com.mongodb.MongoClient();
        MongoDatabase database = client.getDatabase("SearchEngine");
        MongoCollection<Document> toys = database.getCollection("invertedIndex");
        for (int i = 0; i < invertedIndexJSONParameter.size(); i++) {
            Document doc = new Document(invertedIndexJSONParameter.get(i));
            toys.insertOne(doc);
        }
    }

    private static synchronized void fillScoresOfTags() {
        // score of each tag
        //    title = 1
        //    h1 = 0.7
        //    h2 = 0.6
        //    h3 = 0.5
        //    h4 = 0.4
        //    h5 = 0.3
        //    h6 = 0.2
        //    else = 0.1
        tagsOfHtml = new HashMap<String, Double>();
        tagsOfHtml.put("title", 0.9);
        Double j = 0.6;
        for (int i = 1; i <= 6; i++) {
            tagsOfHtml.put("h" + i, j);
            j -= 0.1;
        }
    }

    private static synchronized void filterTags(org.jsoup.nodes.Document html, String fileName) throws IOException {
        PorterStemmer stemmer = new PorterStemmer();
        Pattern pattern = Pattern.compile("\\w+");
        Matcher matcher;
        HashMap<String, Double> tempScore = new HashMap<>();
        //filtration most important tags
        for (String line : tagsOfHtml.keySet()) {
            String taggedString = html.select(line).text();
            if (html != null && !taggedString.isEmpty()) {
                matcher = pattern.matcher(taggedString.toLowerCase());
                while (matcher.find()) {
                    stemmer.setCurrent(matcher.group());
                    stemmer.stem();
                    taggedString = stemmer.getCurrent();
                    if (!tempScore.containsKey(taggedString))
                        tempScore.put(taggedString, tagsOfHtml.get(line));
                    else
                        tempScore.put(taggedString, tempScore.get(taggedString) + tagsOfHtml.get(line));
                }
            }
        }
        scoreOfWords.put(fileName, tempScore);
    }

    private static synchronized void filOtherTags(List<String> stemmedWords, String fileName) {
        HashMap<String, Double> tempScore = new HashMap<>();
        for (String word : stemmedWords) {
            if (tempScore.containsKey(word)) {
                tempScore.put(word, 0.1 + tempScore.get(word));
            } else
                tempScore.put(word, 0.1);
        }
        tempScore.keySet().remove(""); //remove empty string
        scoreOfWords.put(fileName, tempScore);
    }

    // get indices  of each word in each Document
    private static synchronized void getIndexOfWord(List<String> splitWord, StringBuilder originalDoc, String fileName) {
        // TODO: matching actual string not substring in document
        Integer lengthOfDoc = originalDoc.length();
        PorterStemmer stemmer = new PorterStemmer();
        HashMap<String, List<Integer>> tempIndex = new HashMap<>();
        HashSet<Integer> list = new HashSet<>();

        for (String word : splitWord) {
            int startFrom = 0;
            while (true) {
                int index = originalDoc.indexOf(word, startFrom);// get the occurrence of index of each word
                char startChar = '.';
                char endChar = '.';

                if (index - 1 >= 0)
                    startChar = originalDoc.charAt(index - 1);
                if ((index + word.length()) < lengthOfDoc) {
                    endChar = originalDoc.charAt(word.length() + index);
                }

                boolean beforeWord = Character.toString(startChar).matches(".*[a-zA-Z]+.*");
                boolean afterWord = Character.toString(endChar).matches(".*[a-zA-Z]+.*");

                if (index >= 0) {
                    if (!beforeWord && !afterWord)
                        list.add(index);
                    startFrom = index + word.length();
                } else
                    break;
            }

            String lowerWord = word.toLowerCase();
            stemmer.setCurrent(lowerWord);
            stemmer.stem();

            if (list.isEmpty())
                list.add(-2); //indices out of  body

            tempIndex.put(stemmer.getCurrent(), new ArrayList<>(list));
            list.clear();
        }
        tempIndex.keySet().remove("");
        indicesOfWord.put(fileName, tempIndex);
    }

    private static void createBodyFiles(org.jsoup.nodes.Document html, String fileName) {
        try {
            FileWriter myWriter = new FileWriter("bodyFiles//" + fileName);
            myWriter.write(html.title());
            myWriter.write("\n");
            myWriter.write(html.body().text());
            myWriter.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void calculateTF_IDF() {
        for (String word : invertedIndex.keySet()) {
            // Calculate the IDF
            double IDF = (double) document_numbers / (double) invertedIndex.get(word).size();
            for (String doc : invertedIndex.get(word).keySet()) {
                // calculate the normalized tf
                Pair<Integer, Integer, Double, Integer, Integer, Double> pair = invertedIndex.get(word).get(doc);
                double normalizedTF = (double) pair.TF / (double) pair.size;
                // Store the TF_IDF
                invertedIndex.get(word).get(doc).TF_IDF = normalizedTF * IDF;
            }
        }
    }
}