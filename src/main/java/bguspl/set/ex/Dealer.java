package bguspl.set.ex;
import bguspl.set.Env;
import java.util.List;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * This class manages the dealer's threads and data
 */
public class Dealer implements Runnable {
    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;
    private final Player[] players;

    /**
     * The list of card ids that are left in the dealer's deck.
     */
    private final List<Integer> deck;

    /**
     * True iff game should be terminated due to an external event.
     */
    private volatile boolean terminate;

    /**
     * The time when the dealer needs to reshuffle the deck due to turn timeout.
     */
    private long reshuffleTime = Long.MAX_VALUE;

    private Thread[] threads;

    private Queue<Player> SetsToTest;

    private ReentrantLock checklock = new ReentrantLock();


    public Dealer(Env env, Table table, Player[] players) {
        this.env = env;
        this.table = table;
        this.players = players;
        deck = IntStream.range(0, env.config.deckSize).boxed().collect(Collectors.toList());
        threads = new Thread[players.length];
        SetsToTest = new ConcurrentLinkedQueue<>();
    }

    /**
     * The dealer thread starts here (main loop for the dealer thread).
     */
    @Override
    public void run() {
        env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + " starting.");
        boolean canstart = false;
        do {
            placeCardsOnTable();
            if (!canstart) { //initializing it once
                for (int i = 0; i < players.length; i++) { //initialize the threads
                    threads[i] = new Thread(players[i]);
                    threads[i].start();
                }
                canstart = true;
            }
            timerLoop();
            updateTimerDisplay(false);
            removeAllCardsFromTable();
        } while (!shouldFinish());
        announceWinners();
        terminate();
        for (int i= players.length-1;i>=0;i--) {
            try {
                players[i].getPlayerThread().join();
            } catch (InterruptedException e) {
            }
        }
        env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * The inner loop of the dealer thread that runs as long as the countdown did not time out.
     */
    private void timerLoop() {
        reshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis;
        env.ui.setCountdown(env.config.turnTimeoutMillis, false);
        while (!terminate && System.currentTimeMillis() < reshuffleTime) {
            updateTimerDisplay(reshuffleTime - System.currentTimeMillis() < env.config.turnTimeoutWarningMillis);
            sleepUntilWokenOrTimeout();
            if (!SetsToTest.isEmpty()) {
                examine(SetsToTest.poll());
            }

        }
    }

    public ReentrantLock getLock() {
        return checklock;
    }

    public synchronized void HandleTest(Player p) {
        SetsToTest.add(p);
        notifyAll();
    }

    private void examine(Player p) {
        int[] cards = new int[env.config.featureSize]; //featureSize is 3
        boolean isSet = false;
        if (p.gettokensplaced().size() == env.config.featureSize) {
            for (int i = 0; i < cards.length; i++) {
                cards[i] = table.slotToCard[(int) p.gettokensplaced().get(i)];
            }

            isSet = env.util.testSet(cards);
        }
        if (isSet) {
            removeCardsFromTable(cards); //removing the three cards of the set
            placeCardsOnTable();
            p.Wakeup(env.config.pointFreezeMillis);

        } else {
            p.Wakeup(env.config.penaltyFreezeMillis);
        }

        if (isSet) {
            reshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis;
            env.ui.setCountdown(env.config.turnTimeoutMillis, false);
        }
        synchronized (p) { p.notifyAll();}

    }

    /**
     * Called when the game should be terminated due to an external event.
     */
    public void terminate() {
        terminate = true;
        for (int i = players.length - 1; i >= 0; i--) {
            players[i].terminate();
        }
    }

    /**
     * Check if the game should be terminated or the game end conditions are met.
     *
     * @return true iff the game should be finished.
     */
    private boolean shouldFinish() {
        return terminate || env.util.findSets(deck, 1).size() == 0;
    }

    /**
     * Checks cards should be removed from the table and removes them.
     */
    private void removeCardsFromTable(int[] cards) {
        for (int i = 0; i < cards.length; i++) {
            int slot = table.cardToSlot[cards[i]];
            table.removeCard(slot);
            env.ui.removeTokens(slot);
            env.ui.removeCard(slot);
            for (Player player : SetsToTest) { // if the player sent a set to test with this card, we remove the set from the queue
                if (player.gettokensplaced().contains(slot)) {
                    synchronized (player) { player.notifyAll(); } // waking up the player because he no longer has a set to check
                    SetsToTest.remove(player);
                    player.setBlock(false);
                }
            }
            for (int j = 0; j < players.length; j++) {
                if (players[j].getInputPresses().contains(slot))
                    players[j].getInputPresses().remove(slot);
                if (players[j].gettokensplaced().contains(slot))
                    players[j].gettokensplaced().remove((Object) slot);

            }
        }
//        if (shouldFinish()) terminate();
    }

    /**
     * Check if any cards can be removed from the deck and placed on the table.
     */
    private void placeCardsOnTable() {
        for (int i = 0; i < table.slotToCard.length; i++) {
            if (table.slotToCard[i] == null && !(deck.isEmpty())) {
                Random rand = new Random();
                int rnd = rand.nextInt(deck.size());
                table.placeCard(deck.get(rnd), i);
                env.ui.placeCard(deck.get(rnd), i);
                deck.remove(rnd);
            }

        }
    }

    /**
     * Sleep for a fixed amount of time or until the thread is awakened for some purpose.
     */
    private void sleepUntilWokenOrTimeout() {
        int towait = 1000;
        if (reshuffleTime - System.currentTimeMillis() < env.config.turnTimeoutMillis)
            towait = 10;
        try {
            synchronized(this) { wait(towait);}
        } catch (InterruptedException e) {}
    }

    /**
     * Reset and/or update the countdown and the countdown display.
     */
    private void updateTimerDisplay(boolean reset) {
        env.ui.setCountdown(reshuffleTime - System.currentTimeMillis(), reset);
    }

    /**
     * Returns all the cards from the table to the deck.
     */
    private void removeAllCardsFromTable() {
        for (int i = 0; i < table.slotToCard.length; i++) {
            if (table.slotToCard[i] != null) {
                int card = table.slotToCard[i];
                table.removeCard(i);
                deck.add(card);
                env.ui.removeTokens(i);
                env.ui.removeCard(i);
                for (int j = 0; j < players.length; j++) {
                    if (players[j].getInputPresses().contains(i))
                        players[j].getInputPresses().remove(i);
                    if (players[j].gettokensplaced().contains((Object) i))
                        players[j].gettokensplaced().remove((Object) i);
                }
            }

        }
    }

        /**
         * Check who is/are the winner/s and displays them.
         */
        private void announceWinners () {
            int max = 0;
            int count = 0;
            int curr = 0; //the index to put the element in the playersid array
            for (int i = 0; i < players.length; i++) {
                if (max < players[i].getScore()) {
                    count = 0;
                    max = players[i].getScore();
                }
                if (max == players[i].getScore())
                    count++;
            }
            int[] playersId = new int[count];
            for (int i = 0; i < players.length; i++) {
                if (players[i].getScore() == max) {
                    playersId[curr] = players[i].id;
                    curr++;
                }
            }
            env.ui.announceWinner(playersId);
        }
}

