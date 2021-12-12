// IRemoteTaskCallback.aidl
package open.source.multitask;
import open.source.multitask.RemoteTaskException;
import open.source.multitask.RemoteTaskResult;

// Declare any non-default types here with import statements

interface IRemoteTaskCallback {
    void onCompleted(in RemoteTaskResult reult);

    void onException(in RemoteTaskException ex);
}