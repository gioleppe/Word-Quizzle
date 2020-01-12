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
 * WQServer is the core class of the Word Quizzle application. The registration
 * of new users is handled by providing a remote method {@code registerUser}.
 * Newly registered user are then inserted in the server's database which is an
 * instance of the class {@link WQDatabase} thus providing persistence of the
 * users datas.
 * 
 * <p>
 * The WQServer class is also provided with a threadpool {@code tPool} and a
 * selector {@code serverSelector} in order to spawn a limited number of threads
 * thus avoiding excessive load on the cpu and minimizing the overhead due to
 * numerous context changes occurring by assigning one thread per connection.
 * The threadpool is then fed tasks by the selector, whose job is solely to
 * accept new connections and to read the clients requests.
 * 
 * <p>
 * To keep track of the users who are logged in WQServer has a field
 * {@code onlineUsers} of type {@link ConcurrentHashMap} where the keys are the
 * port numbers on which the users clients are connected and the values are
 * their nicknames.
 * 
 * <p>
 * WQServer provides an highly scalable architecture by combining an iterative
 * task dispatching with a concurrent task execution.
 * 
 */
public class WQServer extends UnicastRemoteObject implements WQRegistrationRMI {

    /* ---------------- Fields -------------- */

    /**
     * The serial version UID.
     */
    private static final long serialVersionUID = 1;

    /**
     * The WQDatabase. Initialized upon server creation. It first tries to
     * deserialize, if exists, the db.json file and if it doesn't it creates it.
     */
    private final WQDatabase database;

    /**
     * The threadpool. Initialized upon server creation. The number of threads is
     * currently hardcoded to four as most cpus nowadays have two cores with four
     * threads.
     */
    private final ThreadPoolExecutor tPool;

    /**
     * The selector. Initialized upon server creation.
     */
    private Selector serverSelector;

    /**
     * The channel for the server's socket on which it accepts new connection
     * requests. Initialized upon server creation.
     */
    private ServerSocketChannel serverChannel;

    /**
     * The port on which the server listens. Can be specified by command line.
     */
    private final int portNumber;

    /**
     * The data structure used by WQServer to keep trace of online users. For every
     * logged user u the key is the server port number on which the connection with
     * the client of u is taking place and the value is u's nickname. It's also used
     * to check if the requested operation is legal during task execution.
     */
    private final ConcurrentHashMap<Integer, String> onlineUsers;

    /**
     * The data structure used by WQServer to keep trace of online users IP
     * addresses that will be used for UDP communication. For every logged user u
     * the key is the u's nickname and the value is u's IP address. It's used to
     * send match invitations.
     */
    private final ConcurrentHashMap<String, InetSocketAddress> onlineIPs;

    /**
     * The match duration in minutes. It's an int stating how many minutes a match
     * shall last. It's specified by command line.
     */
    private final int matchDuration;

    /**
     * The match invitation time to live. It's an int stating how many seconds a
     * match invitation shall remain valid. It's specified by command line.
     */
    private final int acceptDuration;

    /**
     * The number of words to be displayed during a match. Is an int stating how
     * many english words the server shall provide to the players. It's specified by
     * command line.
     */
    private final int numWords;

    /* ---------------- Public operations -------------- */

    /**
     * Creates a new WQServer and initializes the threadpool, the selector and the
     * server's socket channel.
     * 
     * @param port       the server port number.
     * @param length     the match duration in minutes.
     * @param invitation the espiry time of a match invitation in seconds.
     * @param words      the number of words to be provided for tradution during a
     *                   match.
     * @throws RemoteException could be be thrown, WQServer is a remote object.
     */
    public WQServer(final int port, final int length, final int invitation, final int words) throws RemoteException {
        super();
        this.database = new WQDatabase();
        this.tPool = new ThreadPoolExecutor(4, 4, 1, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>());
        try {
            this.serverSelector = Selector.open();
            this.serverChannel = ServerSocketChannel.open();
        } catch (final IOException IOE) {
            IOE.printStackTrace();
        }
        this.portNumber = port;
        this.onlineUsers = new ConcurrentHashMap<Integer, String>();
        this.onlineIPs = new ConcurrentHashMap<String, InetSocketAddress>();
        this.matchDuration = length;
        this.acceptDuration = invitation;
        this.numWords = words;
    }

    /**
     * User registration by remote method invocation. Checks the user's nickname and
     * password, if the password is empty or the nickname is already taken returns
     * an error message else proceeds to insert the user in the database and the
     * calls {@code serialize} on the server's WQDatabase instance.
     * 
     * @param username the user's username.
     * @param password the user's password.
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
     * Main method. This is where the logic takes place.
     * 
     * @param args main args
     * @throws RemoteException a remote exception may occur during the execution of
     *                         {@code rebind}.
     */
    public static void main(final String[] args) throws RemoteException {

        // Server creation.
        final WQServer server = new WQServer(8888, 1, 15, 5);

        // Remote method registration
        LocateRegistry.createRegistry(5678);
        final Registry r = LocateRegistry.getRegistry(5678);
        r.rebind("REGISTRATION", server);

        // Setting up the Selector for accepting new connections.
        try {
            final ServerSocket serverSocket = server.serverChannel.socket();
            final InetSocketAddress address = new InetSocketAddress(server.portNumber);
            serverSocket.bind(address);
            server.serverChannel.configureBlocking(false);
            final SelectionKey serverKey = server.serverChannel.register(server.serverSelector, SelectionKey.OP_ACCEPT);
            System.out.println("Listening for connections on port " + server.portNumber + " of host "
                    + InetAddress.getLocalHost().getHostName() + ".");
        } catch (final IOException IOE) {
            IOE.printStackTrace();
        }

        // Server loop
        while (true) {

            try {
                // The number of keys upon which an accept connection operation or a read
                // operation can be performed
                final int readyKeys = server.serverSelector.select();
                if (readyKeys > 0) {
                    // The set containing those keys, and an iterator over this set
                    final Set<SelectionKey> keys = server.serverSelector.selectedKeys();
                    final Iterator<SelectionKey> keysIterator = keys.iterator();
                    // Selector loop: here we check if an accept connection operation or a read
                    // operation can be performed upon the keys in the ready set.
                    // The only key registered for the accept operation
                    // is the one associated with the server's socket and simmetrically
                    // the keys registered for the read opearation are the ones associated with the
                    // clients sockets.
                    while (keysIterator.hasNext()) {
                        // Extract one key
                        final SelectionKey key = keysIterator.next();
                        // The key must be manually removed from the iterator, the selector doesn't
                        // Automatically remove the istances of the SelectionKeys.
                        keysIterator.remove();
                        try {

                            if (key.isAcceptable()) {
                                // Accepting the connection from the client
                                final ServerSocketChannel wqServer = (ServerSocketChannel) key.channel();
                                final SocketChannel wqClient = wqServer.accept();
                                System.out.println(
                                        "Accepted connection from client: " + server.getClientHostname(wqClient));
                                wqClient.configureBlocking(false);
                                // Registering the client socket for read operations
                                final SelectionKey keyClient = wqClient.register(server.serverSelector,
                                        SelectionKey.OP_READ);
                                final ByteBuffer bBuff = ByteBuffer.allocate(512);
                                keyClient.attach(bBuff);

                            } else if (key.isReadable()) {
                                // Reading the socked associated with the key, the key's interest set must be
                                // manually zeroed to avoid concurrency problems between threads, it will be
                                // set again to the read operation by the tasks after their completion.
                                key.interestOps(0);
                                // This boolean is set to true if the user crashses, in fact, if that happens,
                                // the key associated with the client's socket will be readable and '-1'
                                // will be read from it.
                                boolean crash = false;
                                final SocketChannel wqClient = (SocketChannel) key.channel();
                                final ByteBuffer bBuff = (ByteBuffer) key.attachment();
                                final byte[] msg = new byte[128];
                                int index = 0;
                                int k;
                                // Reading the socket
                                while ((k = wqClient.read(bBuff)) != 0 && !crash) {
                                    // Client unexpectedly closed the connection
                                    if (k == -1) {
                                        // If the client crashed a brutal logout is performed.
                                        crash = true;
                                        server.tPool.execute(new LogoutTask(server.onlineUsers, server.onlineIPs,
                                                server.serverSelector, key, true));
                                    }
                                }
                                if (crash)
                                    continue;
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
                                // Splitting the raw arguments, obtaining the processed arguments
                                final String[] procArgs = rawArgs.split(" ");
                                // codo_op is an integer indicating which operation the client requested
                                final Integer cod_op = Integer.parseInt(procArgs[0]);

                                switch (cod_op) {

                                case 0:
                                    // Login Operation.
                                    final String nick = procArgs[1];
                                    final String pwd = procArgs[2];
                                    final int port = Integer.parseInt(procArgs[3]);
                                    final LoginTask logtsk = new LoginTask(server.database, server.onlineUsers,
                                            server.onlineIPs, server.serverSelector, key, nick, pwd, port);
                                    server.tPool.execute(logtsk);
                                    break;
                                case 1:
                                    // Logout Operation.
                                    final LogoutTask unlogtsk = new LogoutTask(server.onlineUsers, server.onlineIPs,
                                            server.serverSelector, key, false);
                                    server.tPool.execute(unlogtsk);
                                    break;
                                case 2:
                                    // Add Friend Operation.
                                    final String friend = procArgs[1];
                                    final AddFriendTask addtsk = new AddFriendTask(server.database, server.onlineUsers,
                                            server.serverSelector, key, friend);
                                    server.tPool.execute(addtsk);
                                    break;
                                case 3:
                                    // Get Friends List Operation.
                                    final GetFriendListTask lsttsk = new GetFriendListTask(server.database,
                                            server.onlineUsers, server.serverSelector, key);
                                    server.tPool.execute(lsttsk);
                                    break;
                                case 4:
                                    // Get Score Operation.
                                    final GetScoreTask scoretsk = new GetScoreTask(server.database, server.onlineUsers,
                                            server.serverSelector, key);
                                    server.tPool.execute(scoretsk);
                                    break;
                                case 5:
                                    // Get Scoreboard Operation.
                                    final GetScoreboardTask boardtsk = new GetScoreboardTask(server.database,
                                            server.onlineUsers, server.serverSelector, key);
                                    server.tPool.execute(boardtsk);
                                    break;
                                case 6:
                                    // Match Operation.
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
     * Utility method to display the client's hostname.
     * 
     * @param client the socket.
     * @return the client's hostname.
     */
    private String getClientHostname(final SocketChannel socketChannel) {
        return socketChannel.socket().getInetAddress().getHostName();
    }
}