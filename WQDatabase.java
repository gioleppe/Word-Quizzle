import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import com.google.gson.*;
import java.io.*;
import java.nio.file.Paths;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;

/**
 * This class take cares of storing and serializing
 * WQServer's persistent informations, such as users' nicknames and
 * passwords, users' friend lists and users' scores. 
 * All the serialization is handled by the {@code Gson} external library.
 */
public class WQDatabase {

     /* ---------------- Fields -------------- */

    /**
     * ConcurrentHashMap used to store the UWUsers.
     * String keys: the users' nicknames.
     * WQUser valuse: the corresponding WQUser objects.
     */
    private ConcurrentHashMap<String, WQUser> WQDB;

    /**
     * WQDatabase constructor.
     */
    public WQDatabase() {
        // opens the path to the db file,
        // if the db already exists calls this.deserialize()
        final File tmp = new File("Database.json");
        final boolean exists = tmp.exists();
        if (!exists) {
            this.WQDB = new ConcurrentHashMap<String, WQUser>();
        } else {
            this.deserialize();
        }
    }

    /**
     * Sets the user's score to the new value.
     * 
     * @param nick the user's nickname.
     * @param scoreVariation    the user's score variation.
     */
    public synchronized void setScore(String nick, int scoreVariation) {
        WQUser user = this.WQDB.get(nick);
        user.updateScore(scoreVariation);
        try {
            // Serializes the DB after the score update
            this.serialize();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    //

    /**
     * Inserts the user in the concurrent hash map if absent and returns true.
     * it returns false if the nickname is already registered. Also serializes the
     * database.
     * 
     * @param nick the user's nickname.
     * @param password the user's password.
     * @return {@code true} if the user has been added {@code false} otherwise.
     */
    public synchronized boolean insertUser(final String nick, final String password) {
        final int hash = password.hashCode();
        final WQUser usr = new WQUser(nick, hash);
        if (WQDB.putIfAbsent(nick, usr) == null) {
            try {
                this.serialize();
            } catch (final IOException ioe) {
                ioe.printStackTrace();
            }
            return true;
        } else {
            return false;
        }
    }

    /**
     * Adds a friend to the user's friendlist.
     * 
     * @param nick       user's nickname.
     * @param friendNick friend's nickname.
     * @return {@code true} if the friend has been added {@code false} otherwise.
     */
    protected boolean addFriend(final String nick, final String friendNick) {
        WQUser user = this.retrieveUser(nick);
        ArrayList<String> uFriends = user.getFriends();
        if (uFriends.contains(friendNick))
            return false;
        else {
            uFriends.add(friendNick);
            ArrayList<String> fFriends = this.retrieveUser(friendNick).getFriends();
            fFriends.add(nick);
            try {
                // serializes after the db update
                this.serialize();
            } catch (IOException IOE) {
                IOE.printStackTrace();
            }
            return true;
        }
    }

    /**
     * Retrieves the user from the db and returns it to the caller.
     * 
     * @param nickname user's nickname.
     * @return the {@code WQUser} named {@code nickname}.
     */
    protected WQUser retrieveUser(final String nickname) {
        final WQUser user = WQDB.get(nickname);
        return user;
    }

    /**
     * Serialization method. 
     * Serializes {@code WQDB} on a .json file called {@code Database.json}
     * 
     * @return a string containing WQDB for debug purposes only.
     * @throws IOException if something strange happens with FileWriter while opening and writing the
     *                     file.
     */
    protected synchronized String serialize() throws IOException {
        final Gson gson = new Gson();
        final String json = gson.toJson(WQDB);
        final FileWriter writer = new FileWriter("./Database.json");
        writer.write(json);
        writer.close();
        return json;
    }

    /**
     * Deserialization method.
     * called on {@code WQServer}'s startup.
     */
    protected void deserialize() {

        String json = null;
        final Gson gson = new Gson();
        //creates a TypeToken for the ConcurrentHashMap. Required by GSON for a correct deserialization. 
        final Type type = new TypeToken<ConcurrentHashMap<String, WQUser>>() {
        }.getType();
        try {
            json = new String(Files.readAllBytes(Paths.get("Database.json")), StandardCharsets.UTF_8);
        } catch (final IOException e) {
            e.printStackTrace();
        }
        final ConcurrentHashMap<String, WQUser> deserialized = gson.fromJson(json, type);
        this.WQDB = deserialized;
    }

}