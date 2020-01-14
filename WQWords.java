import java.util.HashMap;
import java.util.ArrayList;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.io.FileReader;
import java.util.Random;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The WQWords class is used to extract random words from the dictionary
 *  and to request translations to the MyMemory API. 
 */
public class WQWords {

    /* ---------------- Fields -------------- */

    /**
     * The number of words to extract from the dictionary.
     */
    private int wordNum;

    /**
     * The dictionary.
     *  Loaded in order to avoid a lot of system calls opening the file.
     */
    public ArrayList<String> dictionary;

    /**
     * WQWords constructor. Populates the ArrayList dictionary used to extract
     * words.
     * 
     * @param wordNum the number of words to request from the dictionary.
     */
    public WQWords(int wordNum) {
        this.wordNum = wordNum;
        this.dictionary = new ArrayList<String>();
        // opens the dictionary and puts every line in an ArrayList.
        try (BufferedReader reader = new BufferedReader(new FileReader("ItalianDictionary.txt"));) {
            String word;
            while ((word = reader.readLine()) != null) {
                this.dictionary.add(word);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    /**
     * Translates a single word contacting the remote mymemory API. Returns an
     * ArrayList with the translated words list since there may be more than one
     * translation.
     * 
     * @param word the word to translate.
     * @throws IOException if some problems occur during file opening.
     * @return returns an ArrayList of translations to the caller.
     */
    private ArrayList<String> getTranslation(String word) throws IOException {
        ArrayList<String> translations = new ArrayList<String>();
        // building the request URL to connect to.
        String HTTPrequest = "https://api.mymemory.translated.net/get?q=" + word.replace(" ", "%20")
                + "&langpair=it|en";
        URL mymemoryAPI = new URL(HTTPrequest);
        InputStream stream = mymemoryAPI.openStream();
        BufferedReader buff = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
        String inputLine;
        StringBuffer content = new StringBuffer();
        // reading the response to StringBuffer content
        while ((inputLine = buff.readLine()) != null) {
            content.append(inputLine);
        }
        // parsing to json and extracting the translations using GSON
        JsonElement jelement = JsonParser.parseString(content.toString());
        JsonObject jobject = jelement.getAsJsonObject();
        JsonArray jarray = jobject.get("matches").getAsJsonArray();
        for (int i = 0; i < jarray.size(); i++) {
            JsonObject translated = (JsonObject) jarray.get(i);
            String translation = translated.get("translation").getAsString();
            // cleaning junk from the received translations (MyMemory has a lot of weird/nonsense translations)
            translations.add(translation.toLowerCase().replaceAll("[^a-zA-Z0-9\\u0020]", ""));

        }
        return translations;
    }

    /**
     * Extracts random lines from the dictionary and asks
     * for their translation via the getTranslation method.
     * 
     * @throws IOException if something wrong happens during the getTranslation
     *                     call.
     * @return returns an HashMap containing all the extracted words and their corresponding translations.
     */
    protected HashMap<String, ArrayList<String>> requestWords() throws IOException {
        ArrayList<String> selectedWords = new ArrayList<String>();
        HashMap<String, ArrayList<String>> words = new HashMap<String, ArrayList<String>>();
        Random rand = new Random();
        int i = 0;
        while (i < this.wordNum) {
            String word = this.dictionary.get(rand.nextInt(this.dictionary.size()));
            // adds word and increases index only if the word wasn't extracted yet
            if (!selectedWords.contains(word)) {
                selectedWords.add(word);
                i++;
            }
        }
        for (String word : selectedWords) {
            // asks for the translations for the word via the getTranslation method
            ArrayList<String> translations = this.getTranslation(word);
            words.put(word, translations);
        }
        return words;
    }

    /**
     * Main used for testing purposes.
     * 
     * @param args main args.
     * @throws IOException if something strange happens requesting the words.
     */
    public static void main(String[] args) throws IOException {
        WQWords words = new WQWords(5);
        HashMap<String, ArrayList<String>> selectedWords = words.requestWords();
        for (ArrayList<String> translations : selectedWords.values()) {
            System.out.println(translations);
        }
        System.exit(0);
    }
}