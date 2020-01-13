import java.rmi.Remote;
import java.rmi.RemoteException;

/**
 * Interface used for the remote method invocation.
 */
public interface WQRegistrationRMI extends Remote {

    /**
     * Remote method that implements the registration of an user to the
     * {@code WQServer}.
     * 
     * @param username the username you want.
     * @param password the pwd you want to use.
     * @return returns the results of the invocation to the client.
     * @throws RemoteException if something weird happens during the RMI.
     */
    public String registerUser(String username, String password) throws RemoteException;

}
