import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Core class of the Word Quizzle application. Provides user registration via
 * the remote method {@code registerUser}.
 * The server has a database, which is an instance of the class
 * {@link WQDatabase} providing persistent data across server restarts.
 * 
 * <p>
 * The class is provided with a threadpool {@code tPool} and a
 * selector {@code serverSelector} in order to spawn a limited number of threads.
 * This avoids unnecessary load on the cpu and minimizes the overhead that would be
 * present if we adopted a multi threaded solution.
 * The threadpool is fed tasks generated inside the selector loop, whose job is 
 * accepting new connections and to reading client requests.
 * 
 * <p>
 * To keep track of the users who are logged in WQServer, there's a field
 * {@code onlineUsers} of type {@link ConcurrentHashMap} where the keys are the
 * port numbers on which the users clients are connected and the values are
 * their nicknames.
 * 
 * <p>
 * WQServer combines iterative task dispatching with 
 * a concurrent task execution to increase scalability.
 * 
 */
public class WQServer extends UnicastRemoteObject implements WQRegistrationRMI {

    /* ---------------- Fields -------------- */

    /**
     * Serial Version UID representing the class version.
     * Used to ensure correct serialization.
     */
    private static final long serialVersionUID = 1;

    /**
     * The database used to store user data across restarts. 
     */
    private final WQDatabase database;

    /**
     * The threadpool used for task execution. The number of threads is
     * currently hardcoded to four as most cpus nowadays have two cores with four
     * threads.
     */
    private final ThreadPoolExecutor tPool;

    /**
     * The selector used for clients' sockets multiplexing.
     */
    private Selector serverSelector;

    /**
     * The ServerSocketChannel accepting new connection
     * requests.
     */
    private ServerSocketChannel serverChannel;

    /**
     * The port on which the ServerSocketChannel is opened,
     * its value is passed to the WQServer via the constructor method.
     */
    private final int portNumber;

    /**
     * ConcurrentHashMap used by WQServer to track online users. Associates users' nicknames
     * to the server port number on which the connection with each user
     * is taking place. 
     * Also used to check if the requested operation is permitted during task execution.
     */
    private final ConcurrentHashMap<Integer, String> onlineUsers;

    /**
     * ConcurrentHashMap used by WQServer to track online users' IP
     * addresses that will be used for UDP communication purposes. 
     * Associates an username to an IP address.
     * Used to send match invitations.
     */
    private final ConcurrentHashMap<String, InetSocketAddress> onlineIPs;

    /**
     * Match duration in minutes. It's an int stating how many minutes a match
     * shall last. Passed to the constructor method.
     */
    private final int matchDuration;

    /**
     * Match invitation timeout. specifies how many seconds a
     * match invitation shall remain valid. Passed to the constructor method.
     */
    private final int acceptDuration;

    /**
     * Specifies how many words the server shall provide to the players for translation.
     *  Passed to the constructor method.
     */
    private final int numWords;

    /* ---------------- Public operations -------------- */

    /**
     * Creates a new WQServer.
     * Initializes the threadpool, the selector and the server's socket channel.
     * 
     * @param port       ServerSocketChannel port number.
     * @param matchMinutes     Match duration in minutes.
     * @param invitationTO Match invitation timeout in seconds.
     * @param words      Number of words in a match.
     * 
     * @throws RemoteException could be be thrown since WQServer is a remote object.
     */
    public WQServer(final int port, final int matchMinutes, final int invitationTO, final int words) throws RemoteException {
        this.database = new WQDatabase();
        this.tPool = new ThreadPoolExecutor(4, Integer.MAX_VALUE, 100, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>());
        try {
            this.serverSelector = Selector.open();
            this.serverChannel = ServerSocketChannel.open();
        } catch (final IOException IOE) {
            IOE.printStackTrace();
        }
        this.portNumber = port;
        this.onlineUsers = new ConcurrentHashMap<Integer, String>();
        this.onlineIPs = new ConcurrentHashMap<String, InetSocketAddress>();
        this.matchDuration = matchMinutes;
        this.acceptDuration = invitationTO;
        this.numWords = words;
    }

    /**
     * User registration provided via RMI. Checks user's nickname and
     * password, returns an error message if the password is empty or
     * the nickname is already taken, else it proceeds to insert the 
     * user in the database and calls {@code serialize} on the server's WQDatabase instance.
     * 
     * @param username remotely chosen username.
     * @param password remotely chosen password.
     */
    @Override
    public String registerUser(final String username, final String password) {
        if (username == null) {
            System.out.println("SERVER: Invalid username.");
            return "Invalid username";
        }
        if (password == null) {
            System.out.println("SERVER: Invalid password.");
            return "Invalid password";
        }
        if (this.database.insertUser(username, password) == true) {
            System.out.println("SERVER: Registration succeeded");
            return "Registration succeeded";
        } else {
            System.out.println("SERVER: Nickname already taken");
            return "Nickname already taken.";
        }
    }

    /**
     * Entry point for the WQServer.
     * 
     * @param args main args
     * @throws RemoteException in case {@code rebind} fails.
     */
    public static void main(final String[] args) throws RemoteException {

        // Server creation with fixed parameters.
        final WQServer server = new WQServer(8888, 1, 15, 5);

        // Remote method registration on fixed port 5678
        LocateRegistry.createRegistry(5678);
        final Registry r = LocateRegistry.getRegistry(5678);
        r.rebind("REGISTRATION", server);

        // Setting up the Selector.
        try {
            final ServerSocket serverSocket = server.serverChannel.socket();
            final InetSocketAddress address = new InetSocketAddress(server.portNumber);
            serverSocket.bind(address);
            server.serverChannel.configureBlocking(false);
            final SelectionKey serverKey = server.serverChannel.register(server.serverSelector, SelectionKey.OP_ACCEPT);
            System.out.println("Listening for connections on port " + server.portNumber + " of host "
                    + InetAddress.getLocalHost().getHostName());
        } catch (final IOException IOE) {
            IOE.printStackTrace();
        }

        // server loop
        while (true) {

            try {
                // The number of keys, possibly zero, whose ready-operation sets were updated
                final int readyKeys = server.serverSelector.select();
                if (readyKeys > 0) {
                    // gets the set containing the ready keys, and an iterator for the set
                    final Set<SelectionKey> keys = server.serverSelector.selectedKeys();
                    final Iterator<SelectionKey> keysIterator = keys.iterator();
                    // Selector loop: here we check if an accept connection operation or a read
                    // operation can be performed upon the keys in the ready set.
                    // The only key registered for the accept operation
                    // is the one associated with the server's socket and simmetrically
                    // the keys registered for the read opearation are the ones associated with the
                    // clients sockets.
                    while (keysIterator.hasNext()) {
                        // extract a key
                        final SelectionKey key = keysIterator.next();
                        // manually remove the key from the selector, since it doesn't
                        // automatically remove SelectionKeys.
                        keysIterator.remove();
                        try {

                            if (key.isAcceptable()) {
                                // accept connection from the client
                                final ServerSocketChannel wqServer = (ServerSocketChannel) key.channel();
                                final SocketChannel wqClient = wqServer.accept();
                                System.out.println(
                                        "Accepted connection from client: " + server.getClientHostname(wqClient));
                                wqClient.configureBlocking(false);
                                // registers the client socket for read operations
                                final SelectionKey keyClient = wqClient.register(server.serverSelector,
                                        SelectionKey.OP_READ);
                                // allocate a buffer and attach it to the key
                                final ByteBuffer bBuff = ByteBuffer.allocate(512);
                                keyClient.attach(bBuff);

                            } else if (key.isReadable()) {
                                // reads the socket associated with the key, then zeroes the interest set  
                                // to avoid concurrency problems between threads. 
                                // it will be set again to the read operation by the tasks after their completion.
                                key.interestOps(0);
                                // This boolean is set to true if the user crashses in order to correctly remove the
                                // key from the selector. 
                                // in case of a crash the key associated with the client's socket
                                // will be readable and '-1' will be read from it.
                                boolean crash = false;
                                final SocketChannel wqClient = (SocketChannel) key.channel();
                                final ByteBuffer bBuff = (ByteBuffer) key.attachment();
                                final byte[] msg = new byte[128];
                                int index = 0;
                                int k;
                                // reads from the socket channel
                                while ((k = wqClient.read(bBuff)) != 0 && !crash) {
                                    // Client closed the connection => probable crash
                                    if (k == -1) {
                                        // If the client crashed a brutal logout is performed
                                        // logging out the user by force.
                                        crash = true;
                                        server.tPool.execute(new LogoutTask(server.onlineUsers, server.onlineIPs,
                                                server.serverSelector, key, true));
                                    }
                                }
                                if (crash)
                                    continue;
                                // prepares to read from the buffer
                                bBuff.flip();
                                // reads the buffer while there's still something to read
                                while (bBuff.hasRemaining()) {
                                    msg[index] = bBuff.get();
                                    index++;
                                }
                                // builds the raw args byte by byte
                                String rawArgs = "";
                                for (final byte b : msg) {
                                    if ((int) b != 0)
                                        rawArgs += (char) b;
                                }
                                // clears the buffer, resetting 'position' to 0 
                                // and 'limit' to its capacity
                                bBuff.clear();
                                // splits the raw args, obtaining the processed arguments
                                final String[] procArgs = rawArgs.split(" ");
                                // cod_op indicates the operation requested by the client
                                final Integer cod_op = Integer.parseInt(procArgs[0]);

                                switch (cod_op) {

                                case 0:
                                    // login op.
                                    final String nick = procArgs[1];
                                    final String pwd = procArgs[2];
                                    final int port = Integer.parseInt(procArgs[3]);
                                    final LoginTask logtsk = new LoginTask(server.database, server.onlineUsers,
                                            server.onlineIPs, server.serverSelector, key, nick, pwd, port);
                                    server.tPool.execute(logtsk);
                                    break;
                                case 1:
                                    // logout op.
                                    final LogoutTask unlogtsk = new LogoutTask(server.onlineUsers, server.onlineIPs,
                                            server.serverSelector, key, false);
                                    server.tPool.execute(unlogtsk);
                                    break;
                                case 2:
                                    // add friend op.
                                    final String friend = procArgs[1];
                                    final AddFriendTask addtsk = new AddFriendTask(server.database, server.onlineUsers,
                                            server.serverSelector, key, friend);
                                    server.tPool.execute(addtsk);
                                    break;
                                case 3:
                                    // get friend list op.
                                    final GetFriendListTask lsttsk = new GetFriendListTask(server.database,
                                            server.onlineUsers, server.serverSelector, key);
                                    server.tPool.execute(lsttsk);
                                    break;
                                case 4:
                                    // get score op.
                                    final GetScoreTask scoretsk = new GetScoreTask(server.database, server.onlineUsers,
                                            server.serverSelector, key);
                                    server.tPool.execute(scoretsk);
                                    break;
                                case 5:
                                    // get scoreboard op.
                                    final GetScoreboardTask boardtsk = new GetScoreboardTask(server.database,
                                            server.onlineUsers, server.serverSelector, key);
                                    server.tPool.execute(boardtsk);
                                    break;
                                case 6:
                                    // match op.
                                    final String challenged = procArgs[1];
                                    final MatchTask matchtsk = new MatchTask(server.database, server.onlineUsers,
                                            server.onlineIPs, server.serverSelector, key, challenged,
                                            server.matchDuration, server.acceptDuration, server.numWords);
                                    server.tPool.execute(matchtsk);
                                    break;
                                default:
                                    break;
                                }
                            }
                        } catch (final IOException IOE1) {
                            key.cancel();
                            try {
                                key.channel().close();
                            } catch (final IOException IOE2) {
                                IOE2.printStackTrace();
                            }
                        }
                    }
                }
            } catch (final IOException IOE) {
                IOE.printStackTrace();
            }
        }
    }

    /**
     * Displays the client's hostname.
     * 
     * @param socketChannel the client's socket.
     * @return the client's hostname.
     */
    private String getClientHostname(final SocketChannel socketChannel) {
        return socketChannel.socket().getInetAddress().getHostName();
    }
}