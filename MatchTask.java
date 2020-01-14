import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class MatchTask implements Runnable {

    /* ---------------- Fields -------------- */

    /**
     * WQServer's database.
     */
    private final WQDatabase database;

    /**
     * WQServer's online users.
     */
    private final ConcurrentHashMap<Integer, String> onlineusers;

    /**
     * WQServer's online IPs.
     */
    private final ConcurrentHashMap<String, InetSocketAddress> onlineIps;

    /**
     * WQServer's selector.
     */
    private final Selector selector;

    /**
     * SelectionKey with attached the Socket associated with the challenger's
     * client.
     */
    private final SelectionKey key;

    /**
     * Nickname of the friend that the user wants to challenge.
     */
    private final String friend;

    /**
     * Match duration in minutes. It's an int stating how many minutes a match
     * shall last. 
     */
    private final int matchTimer;

    /**
     * Match invitation's time to live. It's an int stating how many seconds a
     * match invitation shall remain valid. 
     */
    private final int acceptTimer;

    /**
     * The number of words used during the match.
     */
    private final int matchWords;

    /**
     * Returns a new MatchTask.
     * 
     * @param datab   database.
     * @param onlineu list of online users.
     * @param onlinei list of online users' UDP addresses.
     * @param sel     selector.
     * @param selk    selection key of interest.
     * @param friends friend to challenge.
     * @param n       match legth.
     * @param m       invite duration.
     * @param l       number of words.
     */
    public MatchTask(final WQDatabase datab, final ConcurrentHashMap<Integer, String> onlineu,
            final ConcurrentHashMap<String, InetSocketAddress> onlinei, final Selector sel, final SelectionKey selk,
            final String frnd, final int n, final int m, final int l) {
        this.database = datab;
        this.onlineusers = onlineu;
        this.onlineIps = onlinei;
        this.selector = sel;
        this.key = selk;
        this.friend = frnd;
        this.matchTimer = n;
        this.acceptTimer = m;
        this.matchWords = l;
    }

    public void run() {

        final SocketChannel clientSocket = (SocketChannel) key.channel();
        final ByteBuffer bBuff = (ByteBuffer) key.attachment();

        String msg;

        // retrieves the user's nickname from the port number
        final int clientPort = clientSocket.socket().getPort();
        final String nickname = onlineusers.get(clientPort);

        // checks if the user nickname is equal to friend's nickname
        if (nickname.equals(friend)) {
            msg = "Match error: you cannot challenge yourself.\n";
            writeMsg(msg, bBuff, clientSocket);
            key.interestOps(SelectionKey.OP_READ);
            selector.wakeup();
            return;
        } else {
            // checks if the two users are friends.
            final WQUser challenger = database.retrieveUser(nickname);
            final ArrayList<String> challengerFriends = challenger.getFriends();
            if (!(challengerFriends.contains(friend))) {
                msg = "Match error: user " + friend + " and you are not friends.\n";
                writeMsg(msg, bBuff, clientSocket);
                key.interestOps(SelectionKey.OP_READ);
                selector.wakeup();
                return;
            } else {
                /// Check if the friend is offline.
                if (!onlineusers.containsValue(friend)) {
                    msg = "Match error: " + friend + " is offline\n";
                    writeMsg(msg, bBuff, clientSocket);
                    key.interestOps(SelectionKey.OP_READ);
                    selector.wakeup();
                    return;
                } else {
                    // start the invitation login.
                    // sets up the UDP socket.
                    DatagramSocket invSocket = null;
                    try {
                        invSocket = new DatagramSocket();
                        invSocket.setSoTimeout(acceptTimer * 1000);
                    } catch (final SocketException e) {
                        e.printStackTrace();
                    }
                    // initializes challenge selector.
                    Selector matchSelector = null;
                    ServerSocketChannel matchChannel = null;
                    try {
                        matchSelector = Selector.open();
                        matchChannel = ServerSocketChannel.open();
                        final ServerSocket matchSocket = matchChannel.socket();
                        final InetSocketAddress address = new InetSocketAddress(0);
                        matchSocket.bind(address);
                        matchChannel.configureBlocking(false);
                        final SelectionKey acceptionKey = matchChannel.register(matchSelector, SelectionKey.OP_ACCEPT);
                    } catch (final IOException IOE) {
                        IOE.printStackTrace();
                    }
                    // the invitation consists in the nickname of the challenger user and a
                    // portnumber.
                    final byte[] invitation = (nickname + "/" + matchChannel.socket().getLocalPort()).getBytes();
                    // gets the challenged user IP address in order to set the packet
                    // destination.
                    final InetSocketAddress friendAddress = onlineIps.get(friend);
                    // sends invitation.
                    sendDatagram(invSocket, invitation, friendAddress);
                    // receives invitation;
                    String response = null;
                    try {
                        response = receiveDatagram(invSocket);
                    } catch (final SocketTimeoutException e) {
                        msg = "Match error: invitation to " + friend + " timed out.\n";
                        // if the invitaiton times out, notifies the friend to delete the pending match
                        // invitation from nickname.
                        byte[] friendMsg = ("TIMEOUT/" + nickname).getBytes();
                        sendDatagram(invSocket, friendMsg, friendAddress);
                        writeMsg(msg, bBuff, clientSocket);
                        key.interestOps(SelectionKey.OP_READ);
                        selector.wakeup();
                        return;
                    } catch (final IOException e) {
                        e.printStackTrace();
                    }
                    // analyzes the response. An eventual refusal from the friend must be
                    // communicated to the challeging client.
                    // if the friend refuses the challenging user must be notified.
                    if (response.equals("N")) {
                        msg = friend + " refused your match invitation.\n";
                        writeMsg(msg, bBuff, clientSocket);
                        key.interestOps(SelectionKey.OP_READ);
                        selector.wakeup();
                        return;
                    } else if (response.equals("Y")) {
                        // The friend accepted the challenge. Must create the challenge socket and
                        // prepare the words.
                        msg = friend + " accepted your match invitation./" + matchChannel.socket().getLocalPort()
                                + "\n";
                        writeMsg(msg, bBuff, clientSocket);
                        boolean joined1 = false;
                        boolean joined2 = false;
                        SocketChannel results1 = null; // challenging user
                        ByteBuffer resultsBuff1 = null;
                        SocketChannel results2 = null; // challenged user
                        ByteBuffer resultsBuff2 = null;
                        int challengedPort = 0;
                        InetAddress add1 = onlineIps.get(nickname).getAddress();
                        InetAddress add2 = onlineIps.get(friend).getAddress();
                        while (!joined1 || !joined2) {
                            try {
                                final int readyKeys = matchSelector.select();
                                if (readyKeys > 0) {
                                    final Set<SelectionKey> keys = matchSelector.selectedKeys();
                                    final Iterator<SelectionKey> keysIterator = keys.iterator();
                                    while (keysIterator.hasNext()) {
                                        // extract one key
                                        final SelectionKey key = keysIterator.next();
                                        // the key must be manually removed from the iterator since the selector
                                        // doesn't automatically remove SelectionKeys.
                                        keysIterator.remove();
                                        if (key.isAcceptable()) {
                                            try {
                                                // accepts the connection from the client
                                                final ServerSocketChannel channel = (ServerSocketChannel) key.channel();
                                                final SocketChannel client = channel.accept();
                                                final InetAddress addr = client.socket().getInetAddress();
                                                final ByteBuffer clientBuff = ByteBuffer.allocate(256);
                                                if (addr.equals(add1) && !joined1) {
                                                    results1 = client;
                                                    joined1 = true;
                                                    resultsBuff1 = clientBuff;
                                                } else if (addr.equals(add2) && !joined2) {
                                                    results2 = client;
                                                    challengedPort = client.socket().getPort();
                                                    joined2 = true;
                                                    resultsBuff2 = clientBuff;
                                                }
                                                client.configureBlocking(false);
                                                // registers the client socket for read operations
                                                final SelectionKey clientKey = client.register(matchSelector,
                                                        SelectionKey.OP_READ);
                                                clientKey.attach(clientBuff);
                                            } catch (final IOException e) {
                                                e.printStackTrace();
                                            }
                                        }
                                    }
                                }

                            } catch (final IOException IOE) {
                                IOE.printStackTrace();
                            }
                        }
                        // requests words from WQWords
                        HashMap<String, ArrayList<String>> dictionary = null;
                        try {
                            dictionary = new WQWords(matchWords).requestWords();
                        } catch (final IOException e) {
                            e.printStackTrace();
                        }
                        final String[] words = dictionary.keySet().toArray(new String[dictionary.size()]);
                        final long startTime = System.currentTimeMillis();
                        long currentTime = System.currentTimeMillis();
                        final String[] response1 = new String[matchWords];
                        final String[] response2 = new String[matchWords];
                        int index1 = 0;
                        int index2 = 0;
                        while (currentTime < (startTime + (matchTimer * 60000))
                                && (index1 < matchWords + 1 || index2 < matchWords + 1)) {
                            try {
                                final int readyKeys = matchSelector.selectNow();
                                if (readyKeys > 0) {
                                    final Set<SelectionKey> keys = matchSelector.selectedKeys();
                                    final Iterator<SelectionKey> keysIterator = keys.iterator();
                                    while (keysIterator.hasNext()) {
                                        // Extract one key
                                        final SelectionKey key = keysIterator.next();
                                        // The key must be manually removed from the iterator, the selector doesn't
                                        // Automatically remove the istances of the SelectionKeys.
                                        keysIterator.remove();
                                        // here is the real match logic.
                                        if (key.isReadable()) {
                                            final SocketChannel clientChann = (SocketChannel) key.channel();
                                            final ByteBuffer clientBuff = (ByteBuffer) key.attachment();
                                            final String translation = readMsg(clientChann, clientBuff);
                                            if (translation.equals("crashed")) {
                                                if (clientChann.socket().getPort() == challengedPort) {
                                                    for (int i = index2 - 1; i < matchWords; i++) {
                                                        response2[i] = "";
                                                    }
                                                    index2 = matchWords + 1;
                                                } else {
                                                    for (int i = index1 - 1; i < matchWords; i++) {
                                                        response1[i] = "";
                                                    }
                                                    index1 = matchWords + 1;
                                                }
                                                key.interestOps(0);
                                            } else {
                                                final String[] split = translation.split("/");
                                                final String name = split[1];
                                                if (translation.equals("START/" + friend)) {
                                                    writeMsg(words[index2] + "\n", clientBuff, clientChann);
                                                    index2++;
                                                } else if (translation.equals("START/" + nickname)) {
                                                    writeMsg(words[index1] + "\n", clientBuff, clientChann);
                                                    index1++;
                                                } else {
                                                    if (name.equals(friend)) {
                                                        response2[index2 - 1] = split[0];
                                                        if (index2 < matchWords) {
                                                            writeMsg(words[index2] + "\n", clientBuff, clientChann);
                                                        }
                                                        index2++;
                                                    } else if (name.equals(nickname)) {
                                                        response1[index1 - 1] = split[0];
                                                        if (index1 < matchWords) {
                                                            writeMsg(words[index1] + "\n", clientBuff, clientChann);
                                                        }
                                                        index1++;
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            } catch (final IOException e) {
                                e.printStackTrace();
                            }
                            currentTime = System.currentTimeMillis();
                        }
                        // prepares to assign scores
                        int score1 = 0;
                        int score2 = 0;
                        final int bonus = 3;
                        String msg1 = "";
                        String msg2 = "";
                        for (int i = 0; i < index1 - 1; i++) {
                            final ArrayList<String> translation = dictionary.get(words[i]);
                            if (translation.contains(response1[i])) {
                                score1 += 2;
                            } else if (response1[i].equals(""))
                                score1 += 0;
                            else
                                score1 -= 1;
                        }
                        for (int i = 0; i < index2 - 1; i++) {
                            final ArrayList<String> translation = dictionary.get(words[i]);
                            if (translation.contains(response2[i])) {
                                score2 += 2;
                            } else if (response2[i].equals(""))
                                score2 += 0;
                            else
                                score2 -= 1;
                        }
                        if (score1 < score2) {
                            score2 += bonus;
                            msg2 = "won";
                            msg1 = "lost";
                        } else if (score2 < score1) {
                            score1 += bonus;
                            msg2 = "lost";
                            msg1 = "won";
                        } else
                            msg1 = msg2 = "drew";
                        if (currentTime < (startTime + (matchTimer * 60000))) {
                            writeMsg("END/You have scored: " + score1 + " points. You " + msg1 + ".\n", resultsBuff1,
                                    results1);
                            writeMsg("END/You have scored: " + score2 + " points. You " + msg2 + ".\n", resultsBuff2,
                                    results2);
                        } else {
                            writeMsg("END/Time out: you have scored: " + score1 + " points. You " + msg1 + ".\n",
                                    resultsBuff1, results1);
                            writeMsg("END/Time out: you have scored: " + score2 + " points. You " + msg2 + "\n",
                                    resultsBuff2, results2);
                        }
                        database.setScore(nickname, score1);
                        database.setScore(friend, score2);
                        key.interestOps(SelectionKey.OP_READ);
                        selector.wakeup();
                        return;
                    }
                }
            }
        }
    }

    /**
     * Utility function. sends a datagram in a UDP socket.
     * 
     * @param socket socket.
     * @param msg    message to insert in the datagram.
     * @param addr   {@code InetSocketAddress} address.
     */
    private void sendDatagram(final DatagramSocket socket, final byte[] msg, final InetSocketAddress addr) {
        final DatagramPacket datagram = new DatagramPacket(msg, msg.length, addr);
        try {
            socket.send(datagram);
        } catch (final IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Utility function. Receives and reads a message from a blocking UDP socket.
     * 
     * @param socket the socket.
     * @return the Datagram received on the socket.
     * @throws SocketTimeoutException if the socket times out.
     * @throws IOException            if something wrong happens during the call to
     *                                {@code receive}.
     */
    private String receiveDatagram(final DatagramSocket socket) throws SocketTimeoutException, IOException {
        final byte[] responseBuffer = new byte[16];
        final DatagramPacket response = new DatagramPacket(responseBuffer, responseBuffer.length);
        socket.receive(response);
        final String responseString = new String(response.getData(), response.getOffset(), response.getLength(),
                StandardCharsets.UTF_8);
        return responseString;
    }

    /**
     * Utility function. Reads a message from a NIO socket.
     * 
     * @param wqClient the socket.
     * @param bBuff    the socket associated byte buffer.
     * @return the message red.
     */
    private String readMsg(final SocketChannel wqClient, final ByteBuffer bBuff) {
        final byte[] msg = new byte[128];
        int index = 0;
        int k;
        boolean crash = false;
        // Reading the socket
        try {
            while ((k = wqClient.read(bBuff)) != 0 && !crash) {
                // Client unexpectedly closed the connection
                if (k == -1) {
                    crash = true;
                }
            }
        } catch (final IOException e) {
            e.printStackTrace();
        }
        // Preparing the buffer for reading form it
        bBuff.flip();
        // Reading from the buffer
        while (bBuff.hasRemaining()) {
            msg[index] = bBuff.get();
            index++;
        }
        // Building the raw arguments
        String rawArgs = "";
        for (final byte b : msg) {
            if ((int) b != 0)
                rawArgs += (char) b;
        }
        // Clearing the buffer, resetting its 'position' to 0 and its 'limit' to its
        // capacity
        bBuff.clear();
        if (crash)
            return "crashed";
        else
            return rawArgs;
    }

    /**
     * Utility function. Writes a message in a NIO socket.
     * 
     * @param msg    the message to write
     * @param bBuff  socket's associated byte buffer.
     * @param socket socket.
     */
    private void writeMsg(final String msg, final ByteBuffer bBuff, final SocketChannel socket) {
        bBuff.put(msg.getBytes());
        bBuff.flip();
        try {
            while (bBuff.hasRemaining()) {
                socket.write(bBuff);
            }
        } catch (final IOException e) {
            e.printStackTrace();
        }
        bBuff.clear();
    }
}