import java.net.Socket;
import java.net.SocketException;
import java.rmi.Remote;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.DatagramSocket;
import java.net.DatagramPacket;
import java.util.concurrent.ConcurrentHashMap;
import java.nio.charset.StandardCharsets;

public class WQClient {

    /* ---------------- Fields -------------- */

    /**
     * The hostname of the computer on which the server is being run.
     */
    private String serverHostname;

    /**
     * The TCP socket used for server communication.
     */
    private Socket clientSock;

    /**
     * The UDP socket used for accepting match invitations.
     */
    private DatagramSocket UDPSock;

    /**
     * The UDPListener class instance. The class implements the runnable interface,
     * it listens for match invitations.
     */
    private MatchListener UDPlistener;

    /**
     * The thread on which UDPListener's run method is bein executed.
     */
    private Thread UDPThread;

    /**
     * The user's nickname associated with this WQClient instance.
     */
    private String myName;

    /**
     * The console used for keyboard input parsing.
     */
    private Console cons;

    /**
     * Where the pending challenges are stored.
     */
    private ConcurrentHashMap<String, DatagramPacket> challengers;

    /**
     * This is the constructor for the WQClient class.
     * 
     * @throws SocketException might be raised if the datagram socket if the socket
     *                         could not be opened, or the socket could not bind to
     *                         the specified local port.
     */
    public WQClient() throws SocketException {
        this.clientSock = new Socket();
        this.UDPSock = new DatagramSocket();
        this.challengers = new ConcurrentHashMap<String, DatagramPacket>();
        this.UDPlistener = new MatchListener(UDPSock, challengers);
        this.UDPThread = new Thread(this.UDPlistener);
        this.UDPThread.start();
        this.cons = System.console();
    }

    /**
     * Handles the RMI to register the user to the database.
     * 
     * @param nick the nickname of the user to be registered.
     * @param pwd  the password of the user.
     * @throws RemoteException might be raised if there's some problem with the
     *                         remote object
     */
    private void registration(final String nick, final String pwd) throws RemoteException {

        WQRegistrationRMI serverObj = null;
        Remote remoteObject = null;
        // opening the registry and locating the remote object from it
        final Registry r = LocateRegistry.getRegistry(5678);
        try {
            remoteObject = r.lookup("REGISTRATION");
            serverObj = (WQRegistrationRMI) remoteObject;
        } catch (final Exception e) {
            e.printStackTrace();
        }

        // calls the remote method and prints the result of the invocation
        System.out.println(serverObj.registerUser(nick, pwd));
    }

    /**
     * Handles the login of an already registered user.
     * 
     * @param nick the nickname you want to login with
     * @param pwd  your password
     * @throws IOException might be raised if an error occurs during the socket
     *                     connection.
     */
    private void login(final String nick, final String pwd) throws IOException {
        // if the socket is not closed and is not connected it means that the WQClient
        // class has just been created and the socket has been initialized but is not
        // connected.
        if (!this.clientSock.isConnected() && !this.clientSock.isClosed()) {
            this.clientSock.connect(new InetSocketAddress(InetAddress.getByName(this.serverHostname), 8888));
        }
        // if the socket is closed it means that a logout has been executed by the
        // client thus the socket has been closed and a new one must be created.
        else if (this.clientSock.isClosed()) {
            this.clientSock = new Socket();
            this.clientSock.connect(new InetSocketAddress(InetAddress.getByName(this.serverHostname), 8888));
        }

        // sends a login request to the server and reads the result into String response
        final String response = this.clientComunicate(this.clientSock,
                "0 " + nick + " " + pwd + " " + UDPSock.getLocalPort());
        System.out.println(response);
        if (response.equals("Login error: wrong password.")) {
            // closes the socket because the login failed
            this.clientSock.close();
        }

        if (response.equals("Login successful.")) {
            // assigns the name to this WQClient instance in order to recycle it for
            // subsequent requests
            this.myName = nick;
        }
    }

    /**
     * Handles the logout of the user currently logged with this client.
     * 
     * @throws IOException if an I/O error occurs when closing the client socket.
     */
    private void logout() throws IOException {
        // check if the user is connected
        if (!this.clientSock.isConnected()) {
            System.out.println("You're not logged in");
            return;
        }
        final String response = this.clientComunicate(this.clientSock, "1");
        System.out.println(response);
        // closes socket if logout is successful
        if (response.equals("Logout successful"))
            this.clientSock.close();
    }

    /**
     * Adds a friend to the user's friendlist.
     * 
     * @param friend the nickname of the user you want to add as a friend
     */
    private void add_friend(final String friend) {
        // check if the user is connected
        if (!this.clientSock.isConnected()) {
            System.out.println("You're not logged in");
            return;
        }
        // sends a friend request and prints the server's response
        System.out.println(this.clientComunicate(this.clientSock, "2" + " " + friend));
    }

    /**
     * Shows the user's friendlist.
     */
    private void friend_list() {
        // check if the user is connected
        if (!this.clientSock.isConnected()) {
            System.out.println("You're not logged in");
            return;
        }
        // sends a friends list request and prints the user's friends.
        System.out.println(this.clientComunicate(this.clientSock, "3"));
    }

    /**
     * Shows the user's score.
     */
    private void score() {
        // check if the user is connected
        if (!this.clientSock.isConnected()) {
            System.out.println("You're not logged in");
            return;
        }
        // requests the score to the server and prints it
        System.out.println(this.clientComunicate(this.clientSock, "4"));
    }

    /**
     * Shows the user's scoreboard.
     */
    private void scoreboard() {
        // check if the user is connected
        if (!this.clientSock.isConnected()) {
            System.out.println("You're not logged in");
            return;
        }
        // sends a scoreboard request to the server and prints the scoreboard for the
        // user himself and his friends
        System.out.println(this.clientComunicate(this.clientSock, "5"));
    }

    /**
     * Sends a match request to a friend.
     * 
     * @param friend the friend you want to match
     * @throws IOException when the newly opened {@code challengeSock} has problems
     *                     during connection
     */
    private void match(final String friend) throws IOException {
        // check if the user is connected
        if (!this.clientSock.isConnected()) {
            System.out.println("You're not logged in");
            return;
        }
        // sends a match request to a friend and blocks until the server communicates
        // acception, then stores answer in String resp
        String resp = this.clientComunicate(this.clientSock, "6" + " " + friend);
        // splits the answer on / because if the match is accepted responseWords[1] has
        // the port of the freshly opened server side socket.
        String[] responseWords = resp.split("/");
        if (responseWords[0].equals(friend + " accepted your match invitation.")) {
            System.out.println(responseWords[0]);
            Socket challengeSock = new Socket();
            // connects to the challenge socket
            challengeSock.connect(new InetSocketAddress(InetAddress.getByName(this.serverHostname),
                    Integer.parseInt(responseWords[1])));
            // starts the match logic
            this.matchLogic(challengeSock);

        } else {
            // if not accepted prints the refusal
            System.out.println(responseWords[0]);
        }
    }

    /**
     * Shows all the pending match requests.
     */
    private void showMatches() {
        // check if the user is connected
        if (!this.clientSock.isConnected()) {
            System.out.println("You're not logged in");
            return;
        }
        // if you don't have any challenge request
        if (this.challengers.isEmpty()) {
            System.out.println("No pending challenges");
            return;
        }
        // else print the requests still pending
        System.out.println("You have received match requests from the following friends:");
        for (String challenger : challengers.keySet()) {
            System.out.println(challenger);
        }

    }

    /**
     * Accepts a match sent by a friend.
     * 
     * @param friend the friend who sent you the request.
     * @throws IOException          if something fishy happens during the
     *                              {@code connect()}.
     * @throws InterruptedException if something fishy happens during the
     *                              {@code sleep()}.
     */
    private void acceptMatch(String friend) throws IOException, InterruptedException {
        // check if the user is connected
        if (!this.clientSock.isConnected()) {
            System.out.println("You're not logged in");
            return;
            // if you have no pending challenges
        } else if (this.challengers.isEmpty()) {
            System.out.println("No pending challenges");
            return;
            // if your friend didn't challenge you
        } else if (!challengers.containsKey(friend)) {
            System.out.println("User " + friend + " didn't challenge you");
            return;
        }

        // gets the ip address and the port of the DatagramSocket used by the server
        // using the information contained in the received packet.
        InetAddress sockAddr = challengers.get(friend).getAddress();
        int port = challengers.get(friend).getPort();

        byte[] buf = "Y".getBytes();
        // creates the acceptance datagram and sends it
        DatagramPacket acceptance = new DatagramPacket(buf, buf.length, sockAddr, port);
        UDPSock.send(acceptance);
        DatagramPacket response = challengers.get(friend);
        String challenger = new String(response.getData(), response.getOffset(), response.getLength(),
                StandardCharsets.UTF_8);
        int TCPport = Integer.parseInt(challenger.substring(challenger.indexOf("/") + 1));
        // since we accepted the match we remove the pending challenge request
        challengers.remove(friend);
        // sends refusal packets to the other pending challengers
        for (DatagramPacket refusedFriend : challengers.values()) {
            buf = "N".getBytes();
            DatagramPacket refusal = new DatagramPacket(buf, buf.length, refusedFriend.getAddress(),
                    refusedFriend.getPort());
            UDPSock.send(refusal);
        }
        // removes all the pending challengers from the hashmap
        challengers.clear();
        Thread.sleep(2000);
        // creates and opens up a new socket where the matchLogic communication will
        // take place
        Socket challengeSock = new Socket();
        challengeSock.connect(new InetSocketAddress(InetAddress.getByName(this.serverHostname), TCPport));
        this.matchLogic(challengeSock);
    }

    /**
     * Implements the logic of the match, waiting for words and sending user
     * translations back to the server.
     * 
     * @param sock the TCP socket opened for the match
     */
    private void matchLogic(Socket sock) {

        String input;
        String resp = clientComunicate(sock, "START/" + this.myName);
        System.out.println("Translate all the following words:");
        System.out.println("Server: " + resp);
        String[] responseWords = resp.split(" ");
        while (!responseWords[0].equals("END")) {
            System.out.print("Translation: ");
            input = this.cons.readLine();
            input += "/" + this.myName;
            System.out.print("Server: ");
            resp = clientComunicate(sock, input);
            responseWords = resp.split("/");
            if (responseWords[0].equals("END")) {
                break;
            } else {
                System.out.println(resp);
            }
        }
        System.out.println(resp.substring(resp.indexOf("/") + 1));
        return;
    }

    /**
     * Parses the command line and calls the correct method in order to meet the
     * user's needs.
     * 
     * @param input the command line input read by the Console class
     * @throws IOException          might be raised by login, logout or accept_match
     *                              methods
     * @throws RemoteException      might be raised by the registration method
     * @throws InterruptedException might be raised by the accept match method
     */
    private void parseInput(final String input) throws IOException, RemoteException, InterruptedException {
        final String[] params = input.split(" ");
        switch (params[0]) {
        case "registration":
            this.registration(params[1], params[2]);
            break;
        case "login":
            this.login(params[1], params[2]);
            break;
        case "logout":
            this.logout();
            break;
        case "add_friend":
            this.add_friend(params[1]);
            break;
        case "friend_list":
            this.friend_list();
            break;
        case "score":
            this.score();
            break;
        case "scoreboard":
            this.scoreboard();
            break;
        case "match":
            this.match(params[1]);
            break;
        case "show_matches":
            this.showMatches();
            break;
        case "accept_match":
            this.acceptMatch(params[1]);
            break;
        default:
            System.out.println("wrong usage");
            break;
        }
    }

    /**
     * Sends a message to the specified socket and returns a string with the
     * response to the caller.
     * 
     * @param sock the TCP socket where you wish to send the message
     * @param arg  the message you want to send
     * @return the returned string is the remote server's response.
     */
    private String clientComunicate(final Socket sock, final String arg) {
        // gets input and output streams from the socket passed as argument
        InputStream dataIn = null;
        OutputStream dataOut = null;
        try {
            dataIn = sock.getInputStream();
            dataOut = sock.getOutputStream();
        } catch (final Exception e) {
            e.printStackTrace();
        }

        // creates a new InputStreamReader and decorates it with a BufferedReader
        final InputStreamReader isr = new InputStreamReader(dataIn);
        final BufferedReader in = new BufferedReader(isr);

        byte[] request;
        request = arg.getBytes();
        try {
            // sends the request on the socket
            dataOut.write(request);
        } catch (final Exception e) {
            e.printStackTrace();
        }

        String response = null;
        try {
            // blocks waiting from an answer from the server
            response = in.readLine();
        } catch (final IOException e) {
            e.printStackTrace();
        }
        return response;
    }

    public static void main(final String[] args) throws RemoteException, IOException, InterruptedException {

        if (args[0].equals("--help")) {
            System.out
                    .println("Commands:" + "\n" + "registration <username> <password>: registers to the remote service"
                            + "\n" + "login <username> <password>: logs in an user" + "\n" + "logout: logs out the user"
                            + "\n" + "add_friend <nickFriend>: adds nickFriend as a friend" + "\n"
                            + "friend_list: shows the friend lists" + "\n"
                            + "match <nickFriend>: sends a match request to a friend" + "\n"
                            + "show_matches: shows the pending match invitations" + "\n"
                            + "accept_match: <nickFriend>: accepts nickFriend's match invitation" + "\n"
                            + "score: shows the user's score" + "\n" + "scoreboard: shows the user's scoreboard" + "\n"
                            + "quit: exits the WQWords client");
            System.exit(0);
        }

        String input = null;

        // creates a new WQClient instance
        final WQClient client = new WQClient();

        try {
            // gets the hostname from the command line
            client.serverHostname = args[0];
        } catch (NullPointerException e) {
            // if no hostname uses localhost instead
            client.serverHostname = "localhost";
        }

        Thread.sleep(500);
        while (true) {

            // reads input from console
            input = client.cons.readLine("%s ", ">");

            // if the user asks for a login takes username and pwd from command line
            if (input.equals("login")) {
                String username;
                char[] passwd;
                if ((username = client.cons.readLine("%s ", "Username:")) != null
                        && (passwd = client.cons.readPassword("%s ", "Password:")) != null) {
                    input += " " + username + " " + String.valueOf(passwd);
                    client.cons.flush();
                }
            }
            // it reads quit exit the loop
            if (input.equals("quit")) {
                System.out.println(client.challengers.toString());
                break;
            }
            client.parseInput(input);
        }
        System.exit(0);
    }

}

/**
 * This class implements the UDP socket listener waiting to receive match
 * invitations.
 * 
 */
class MatchListener implements Runnable {

    DatagramSocket UDPSocket;
    ConcurrentHashMap<String, DatagramPacket> challengers;

    /**
     * The constructor to MatchListener.
     * 
     * @param UDPSock     the UDP socket where the listener will wait for
     *                    invitations
     * @param challengers the HashMap all pending invitations are put
     */
    MatchListener(DatagramSocket UDPSock, ConcurrentHashMap<String, DatagramPacket> challengers) {
        this.UDPSocket = UDPSock;
        this.challengers = challengers;
    }

    public void run() {
        System.out.println("Started Listener thread");
        while (true) {
            byte[] buf = new byte[512];
            DatagramPacket response = new DatagramPacket(buf, buf.length);
            try {
                // receives datagrams
                UDPSocket.receive(response);
            } catch (IOException e) {
                e.printStackTrace();
            }
            // gets the content from the packet
            String contentString = new String(response.getData(), response.getOffset(), response.getLength(),
                    StandardCharsets.UTF_8);
            // if timed out message removes the challenger from the pending list
            if (contentString.substring(0, contentString.indexOf("/")).equals("TIMEOUT")) {
                String timedOutChallenger = contentString.substring(contentString.indexOf("/") + 1);
                challengers.remove(timedOutChallenger);
                System.out.println(timedOutChallenger + "'s match request timed out.");
                continue;
            }
            String challenger = contentString.substring(0, contentString.indexOf("/"));
            System.out.println("Received a challenge from: " + challenger);
            System.out.print(">");
            // puts the challenger in the pending list
            challengers.put(challenger, response);

        }
    }
}