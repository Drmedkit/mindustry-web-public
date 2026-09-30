import arc.Application;
import arc.ApplicationListener;
import arc.Core;
import arc.struct.Seq;
import arc.util.TaskQueue;
import arc.util.Timer;

/** Focused JVM check for the opt-in browser timer strategy. */
public final class TimerThreadlessCheck{
    public static void main(String[] args) throws Exception{
        check(!Timer.isThreadlessFallback(), "threaded timers must remain the default");

        // Browser configuration must be possible before an Application has been installed in Core.
        Timer.setThreadlessFallback(true);
        TestApplication app = new TestApplication();
        Core.app = app;
        Core.files = null;

        int timerThreadsBefore = timerThreadCount();
        final int[] immediateRuns = {0};
        Timer.post(new Timer.Task(){
            @Override
            public void run(){
                immediateRuns[0]++;
            }
        });

        check(app.listeners.size == 1, "the main-loop timer must register one application listener");
        check(timerThreadCount() == timerThreadsBefore, "the main-loop timer must not spawn a Timer thread");

        app.frame();
        check(immediateRuns[0] == 0, "a posted task must not run before the following frame");
        app.frame();
        check(immediateRuns[0] == 1, "a posted task must run on the application loop");
        check(app.lastRunnableThread == Thread.currentThread(), "timer tasks must run on the main loop thread");

        final int[] delayedRuns = {0};
        Timer.schedule(new Timer.Task(){
            @Override
            public void run(){
                delayedRuns[0]++;
            }
        }, 0.02f);

        Thread.sleep(35L);
        check(delayedRuns[0] == 0 && app.pendingRunnables() == 0,
        "elapsed wall time alone must not advance a main-loop timer");

        app.frame();
        check(delayedRuns[0] == 0 && app.pendingRunnables() == 1,
        "the listener update must enqueue a newly-due task");
        app.frame();
        check(delayedRuns[0] == 1, "the next frame must execute the newly-due task");

        System.out.println("TimerThreadlessCheck passed");
    }

    private static int timerThreadCount(){
        int count = 0;
        for(Thread candidate : Thread.getAllStackTraces().keySet()){
            if(candidate.isAlive() && "Timer".equals(candidate.getName())) count++;
        }
        return count;
    }

    private static void check(boolean condition, String message){
        if(!condition) throw new AssertionError(message);
    }

    private static final class TestApplication implements Application{
        final Seq<ApplicationListener> listeners = new Seq<>();
        final TaskQueue runnables = new TaskQueue();
        Thread lastRunnableThread;

        @Override
        public Seq<ApplicationListener> getListeners(){
            return listeners;
        }

        @Override
        public ApplicationType getType(){
            return ApplicationType.web;
        }

        @Override
        public String getClipboardText(){
            return null;
        }

        @Override
        public void setClipboardText(String text){
        }

        @Override
        public void post(Runnable runnable){
            runnables.post(() -> {
                lastRunnableThread = Thread.currentThread();
                runnable.run();
            });
        }

        @Override
        public void exit(){
        }

        int pendingRunnables(){
            return runnables.size();
        }

        void frame(){
            runnables.run();
            synchronized(listeners){
                for(ApplicationListener listener : listeners){
                    listener.update();
                }
            }
        }
    }
}
