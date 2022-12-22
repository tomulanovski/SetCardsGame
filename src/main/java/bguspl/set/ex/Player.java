package bguspl.set.ex;

import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;

import bguspl.set.Env;

/**
 * This class manages the players' threads and data
 *
 * @inv id >= 0
 * @inv score >= 0
 */
public class Player implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;

    /**
     * The id of the player (starting from 0).
     */
    public final int id;

    /**
     * The thread representing the current player.
     */
    private Thread playerThread;

    /**
     * The thread of the AI (computer) player (an additional thread used to generate key presses).
     */
    private Thread aiThread;

    /**
     * True iff the player is human (not a computer player).
     */
    private final boolean human;

    /**
     * True iff game should be terminated due to an external event.
     */
    private volatile boolean terminate;

    private final ReentrantLock checklock;

    /**
     * The current score of the player.
     */
    private int score;

    private Dealer dealer;

    private final List<Integer> tokensplaced;

    private boolean keyBlock;


    private final BlockingQueue<Integer> inputpresses;

    private long sleeptime;

    private final Object ailock = new Object();

    private final Object PressLock = new Object();

    /**
     * The class constructor.
     *
     * @param env    - the environment object.
     * @param dealer - the dealer object.
     * @param table  - the table object.
     * @param id     - the id of the player.
     * @param human  - true iff the player is a human player (i.e. input is provided manually, via the keyboard).
     */
    public Player(Env env, Dealer dealer, Table table, int id, boolean human ) {
        this.env = env;
        this.table = table;
        this.id = id;
        this.human = human;
        this.dealer = dealer;
        this.tokensplaced = new LinkedList<>();
        this.keyBlock = true;
        inputpresses = new LinkedBlockingQueue<>(env.config.featureSize);
        this.checklock = dealer.getLock();
    }

    /**
     * The main player thread of each player starts here (main loop for the player thread).
     */
    @Override
    public void run() {
        keyBlock = false;
        playerThread = Thread.currentThread();
        env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + "starting.");
        if (!human) createArtificialIntelligence();
        while (!terminate) {
            try {
                synchronized (PressLock) {PressLock.wait();} // player is in wait while no key pressed
            }catch (InterruptedException e){}
            if (sleeptime == env.config.penaltyFreezeMillis) {
                penalty();
            } else if (sleeptime == env.config.pointFreezeMillis)
                point();
            if (!inputpresses.isEmpty()) {
                int slot = inputpresses.poll();
                handleKeyPress(slot);
            }
        }
        if (!human) try {
            aiThread.join();
        } catch (InterruptedException ignored) {
        }
        env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + " terminated.");
    }


    /**
     * Creates an additional thread for an AI (computer) player. The main loop of this thread repeatedly generates
     * key presses. If the queue of key presses is full, the thread waits until it is not full.
     */
    private void createArtificialIntelligence() {
        // note: this is a very very smart AI (!)
        aiThread = new Thread(() -> {
            env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + " starting.");
            while (!terminate) {
                Random rand = new Random();
                int rndslot = rand.nextInt(table.slotToCard.length);
                keyPressed(rndslot);
                try {
                    if(!terminate) {
                        synchronized (ailock) {
                            ailock.wait(1);
                        }
                    }
                } catch (InterruptedException ignored) {

                }
            }
            env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + " terminated.");
        }, "computer-" + id);
        aiThread.start();
    }

    public List gettokensplaced() {
        return tokensplaced;
    }


    /**
     * Called when the game should be terminated due to an external event.
     */
    public void terminate() {
        terminate = true;
        synchronized (PressLock) { PressLock.notifyAll();} // waking up sleeping player waiting for press
        synchronized (this) {notifyAll();} // waking up sleep player waiting for set check

    }

    /**
     * This method is called when a key is pressed.
     *
     * @param slot - the slot corresponding to the key pressed.
     */
    public void keyPressed(int slot) {
        if (inputpresses.size()<env.config.featureSize && !keyBlock && table.slotToCard[slot] != null) {
            inputpresses.add(slot);
        }
        synchronized (PressLock) { PressLock.notifyAll(); } //waking up the player
    }

    private void handleKeyPress(int slot) {
        if (tokensplaced.size() < env.config.featureSize) {
            if (tokensplaced.contains(slot)) {
                tokensplaced.remove((Object) slot);
                env.ui.removeToken(this.id, slot);
            }
            else {
                if(table.slotToCard[slot]!=null) { //checking that there is a card on this slot at the moment
                    tokensplaced.add(slot); //adding to the queue of the player tokens
                    env.ui.placeToken(this.id, slot);
                    if (tokensplaced.size() == 3) {
                        boolean ans = checklock.tryLock(); //trying to acquire the lock and returning if was able to or not
                        keyBlock = true;
                        boolean done=false;
                        while (!done && !terminate) {
                        if (ans) {
                            try {
                            dealer.HandleTest(this);
                            }
                            finally {
                                checklock.unlock();
                                try {
                                    synchronized (this) { wait();}
                                }catch (InterruptedException e){}
                                done=true;
                            }
                            }
                        else {
                            ans = checklock.tryLock();
                        }
                        }
                    }
                }
            }
        }
        else if (tokensplaced.size() == 3 && tokensplaced.contains(slot)) {
            tokensplaced.remove((Object) slot);
            env.ui.removeToken(this.id, slot);
        }
    }

    /**
     * Award a point to a player and perform other related actions.
     *
     * @post - the player's score is increased by 1.
     * @post - the player's score is updated in the ui.
     */
    public void point() {
        int ignored = table.countCards(); // this part is just for demonstration in the unit tests
        env.ui.setScore(id, ++score);
        long freezetime = System.currentTimeMillis() + env.config.pointFreezeMillis;
        while (System.currentTimeMillis() <= freezetime) {
            env.ui.setFreeze(id, freezetime - System.currentTimeMillis());
            try {
                synchronized(this) { wait(900);}
            } catch (InterruptedException e) {}
        }
        sleeptime = 0;
        keyBlock=false;
    }

    /**
     * Penalize a player and perform other related actions.
     */
    public void penalty() {
        long freezetime = System.currentTimeMillis() + env.config.penaltyFreezeMillis;
        while (System.currentTimeMillis() <= freezetime) {
            env.ui.setFreeze(id, freezetime - System.currentTimeMillis());
            try {
                synchronized (this) {
                    wait(900);
                }
            } catch (InterruptedException e) {}
        }
            sleeptime = 0;
            keyBlock = false;
        }
    public void Wakeup(long sleeptime) {
        this.sleeptime = sleeptime;
    }


    public int getScore() {
        return score;
    }
    public Queue getInputPresses () {
        return inputpresses;
    }
    public void setBlock(boolean toblock) {
        keyBlock = toblock;
    }

    public Thread getPlayerThread() {
        return playerThread;
    }

    public Object getLock(){
        return PressLock;
    }

}