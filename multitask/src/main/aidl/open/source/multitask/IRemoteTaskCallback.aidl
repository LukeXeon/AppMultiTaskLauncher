// IRemoteTaskCallback.aidl
package open.source.multitask;
import open.source.multitask.RemoteTaskException;

// Declare any non-default types here with import statements

interface IRemoteTaskCallback {
    void onCompleted();

    void onException(in RemoteTaskException ex);
}