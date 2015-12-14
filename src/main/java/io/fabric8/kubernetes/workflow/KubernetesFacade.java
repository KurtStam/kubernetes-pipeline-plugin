package io.fabric8.kubernetes.workflow;

import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.dsl.LogWatch;

import java.io.Closeable;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class KubernetesFacade {

    private static final String RUNNING_PHASE = "Running";
    private static final String SUCCEEDED_PHASE = "Succeeded";
    private static final String FAILED_PHASE = "Failed";

    private static final KubernetesClient CLIENT = new DefaultKubernetesClient();
    private static final Map<String, Set<Closeable>> CLOSEABLES = new HashMap<>();

    public static Pod createPod(String podName, String image, String workspace, List<EnvVar> env, String cmd) {
        Pod pod = CLIENT.pods().createNew()
                .withNewMetadata()
                .withName(podName)
                .addToLabels("owner", "jenkins")
                .endMetadata()
                .withNewSpec()
                .addNewVolume()
                .withName("workspace")
                .withNewHostPath(workspace)
                .endVolume()
                .withServiceAccount("fabric8")
                .addNewContainer()
                .addNewVolumeMount()
                .withName("workspace")
                .withMountPath(workspace)
                .endVolumeMount()
                .withName("podstep")
                .withImage(image)
                .withEnv(env)
                .withWorkingDir(workspace)
                .withCommand("/bin/sh", "-c")
                .withArgs(cmd) // Always get the last part
                .withTty(false) //It screws up getLog() if tty = true
                .endContainer()
                .withRestartPolicy("Never")
                .endSpec()
                .done();

        synchronized (CLOSEABLES) {
            CLOSEABLES.put(podName, new HashSet<Closeable>());
        }
        return pod;
    }

    public static Boolean deletePod(String podName) {
        if (CLIENT.pods().withName(podName).delete()) {
            synchronized (CLOSEABLES) {
                for (Closeable c : CLOSEABLES.remove(podName)) {
                    closeQuietly(c);
                }
            }
            return true;
        } else {
            return false;
        }
    }

    public static LogWatch watchLogs(String podName) {
        LogWatch watch = CLIENT.pods().withName(podName).watchLog();
        synchronized (CLOSEABLES) {
            CLOSEABLES.get(podName).add(watch);
        }
        return watch;
    }

    public static Watch watch(final String podName, AtomicBoolean alive, CountDownLatch started, CountDownLatch finished, Boolean cleanUpOnFinish) {
        Callable<Void> onCompletion = cleanUpOnFinish ? new Callable<Void>() {
            @Override
            public Void call() throws Exception {
                cleanUp(podName);
                return null;
            }
        } : null;

        Watch watch = CLIENT.pods().withName(podName).watch(new PodWatcher(alive, started, finished, onCompletion));
        synchronized (CLOSEABLES) {
            CLOSEABLES.get(podName).add(watch);
        }
        return watch;
    }

    public static final boolean isPodRunning(Pod pod) {
        return pod != null && pod.getStatus() != null && RUNNING_PHASE.equals(pod.getStatus().getPhase());
    }

    public static final boolean isPodCompleted(Pod pod) {
        return pod != null && pod.getStatus() != null &&
                (SUCCEEDED_PHASE.equals(pod.getStatus().getPhase()) || FAILED_PHASE.equals(pod.getStatus().getPhase()));
    }

    private static void cleanUp(String podName) {
        synchronized (CLOSEABLES) {
            for (Closeable c : CLOSEABLES.remove(podName)) {
                closeQuietly(c);
            }
        }
    }

    private static void closeQuietly(Closeable c) {
        try {
            c.close();
        } catch (IOException e) {
            //ignore
        }
    }
}