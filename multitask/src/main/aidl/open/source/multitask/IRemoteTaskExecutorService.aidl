// IRemoteTaskExecutorService.aidl
package open.source.multitask;
import open.source.multitask.IRemoteTaskCallback;
import open.source.multitask.ParcelKeyValue;

// Declare any non-default types here with import statements

interface IRemoteTaskExecutorService {
    /**
     * Demonstrates some basic types that you can use as parameters
     * and return values in AIDL.
     */
    void execute(
        String name,
        String type,
        boolean isAsync,
        String process,
        in List<String> dependencies,
        in List<ParcelKeyValue> results,
        in IRemoteTaskCallback callback
    );
}