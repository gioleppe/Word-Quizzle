import java.util.ArrayList;

/**
 * The WQUser class models the Word Quizzle User. 
 * Implements the Comparable interface in order to sort users for the scoreboard.
 */
public class WQUser implements Comparable<WQUser> {

     /* ---------------- Fields -------------- */

    /**
     * User's nickname.
     */
    private final String nickname;

    /**
     * User's score.
     */
    private Integer score;

    /**
     * User's password hash.
     */
    private final int pwdHash;

    /**
     * User's friends.
     */
    private final ArrayList<String> friends = new ArrayList<String>();

    /**
     * WQUser's constructor method.
     * 
     * @param nickname user's nickname.
     * @param pwdHash  user's password.
     */
    public WQUser(final String nickname, final int pwdHash) {
        this.nickname = nickname;
        this.pwdHash = pwdHash;
        this.score = 0;
    }

    /**
     * Getter method. Returns the user's nickname to the caller.
     * 
     * @return this user's nickname.
     */
    protected String getNickname() {
        return this.nickname;
    }

    /**
     * Getter method. Returns the user's score to the caller.
     * 
     * @return this user's score.
     */
    protected Integer getScore() {
        return this.score;
    }

    /**
     * Getter method. Returns the user's password hash.
     * 
     * @return this user's password hash.
     */
    protected int getPwdHash() {
        return this.pwdHash;
    }

    /**
     * Getter method. Returns the user's friend list.
     * 
     * @return the user's friend list.
     */
    protected ArrayList<String> getFriends() {
        return this.friends;
    }

    /**
     * Updates the user's score. called by setScore to update
     * the score of the players after every match.
     * 
     * @param difference the variation to the user's score.
     */
    protected synchronized void updateScore(final Integer difference) {
        this.score += difference;
    }

    /**
     * Compare method. Used to sort the scoreboard.
     * 
     * @param user the user to be compared to.
     */
    @Override
    public int compareTo(final WQUser user) {
        return -1 * Integer.compare(this.score, user.score);
    }
}