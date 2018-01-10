package nl.tudelft.blockchain.scaleoutdistributedledger.simulation.transactionpattern;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import nl.tudelft.blockchain.scaleoutdistributedledger.LocalStore;
import nl.tudelft.blockchain.scaleoutdistributedledger.TransactionCreator;
import nl.tudelft.blockchain.scaleoutdistributedledger.model.Block;
import nl.tudelft.blockchain.scaleoutdistributedledger.model.BlockAbstract;
import nl.tudelft.blockchain.scaleoutdistributedledger.model.Chain;
import nl.tudelft.blockchain.scaleoutdistributedledger.model.Node;
import nl.tudelft.blockchain.scaleoutdistributedledger.model.OwnNode;
import nl.tudelft.blockchain.scaleoutdistributedledger.model.Transaction;
import nl.tudelft.blockchain.scaleoutdistributedledger.simulation.SimulationMode;

/**
 * Random transaction pattern that uses a uniform distribution.
 */
public class RandomTransactionPattern implements ITransactionPattern {
	private static final long serialVersionUID = 6328897872068347550L;
	
	private int minWaitTime;
	private int maxWaitTime;
	private int minAmount;
	private int maxAmount;
	private int commitEvery;
	private Long seed;
	private transient Random random;
	private transient boolean listUpdated;

	/**
	 * @param minAmount   - the minimal transaction amount
	 * @param maxAmount   - the maximal transaction amount
	 * @param minWaitTime - the minimum time to wait
	 * @param maxWaitTime - the maximum time to wait
	 * @param commitEvery - commit to the main chain after this amount of blocks
	 */
	public RandomTransactionPattern(int minAmount, int maxAmount, int minWaitTime, int maxWaitTime, int commitEvery) {
		this.minAmount = minAmount;
		this.maxAmount = maxAmount;
		this.minWaitTime = minWaitTime;
		this.maxWaitTime = maxWaitTime;
		this.commitEvery = commitEvery;
		this.random = new Random();
	}

	/**
	 * Sets the seed that is used for random number generation.
	 * @param seed - the seed
	 */
	public void setSeed(long seed) {
		this.seed = seed;
		this.random.setSeed(seed);
	}

	@Override
	public String getName() {
		return "Random";
	}
	
	@Override
	public SimulationMode getSimulationMode() {
		return SimulationMode.DISTRIBUTED;
	}

	@Override
	public long timeUntilNextAction(LocalStore localStore) {
		return minWaitTime + random.nextInt(maxWaitTime - minWaitTime);
	}

	/**
	 * Selects a random node to send money to.
	 * @param localStore - the local store
	 * @return           - a randomly selected node
	 */
	public Node selectNode(LocalStore localStore) {
		if (!listUpdated) {
			localStore.updateNodes();
			listUpdated = true;
		}
		
		//TODO the selection should not include ourselves!
		int amount = localStore.getNodes().size();
		int selected = random.nextInt(amount);
		return localStore.getNode(selected);
	}
	
	/**
	 * Selects a random amount of money.
	 * @param localStore - the local store
	 * @return           - a randomly selected amount or -1 if not enough money
	 */
	public long selectAmount(LocalStore localStore) {
		long available = localStore.getAvailableMoney();
		if (available == 0 || minAmount > available) return -1;
		
		long amount = (long) minAmount + random.nextInt(maxAmount - minAmount);
		return Math.min(amount, available);
	}
	
	/**
	 * @param uncommittedBlocks - the number of blocks that have not yet been committed
	 * @return                  - true if we should commit now
	 */
	public boolean shouldCommitBlocks(int uncommittedBlocks) {
		return uncommittedBlocks >= commitEvery;
	}

	@Override
	public void doAction(LocalStore localStore) throws InterruptedException {
		//Select receiver and amount
		long amount = selectAmount(localStore);
		if (amount == -1) return;
		
		Node receiver = selectNode(localStore);
		
		OwnNode ownNode = localStore.getOwnNode();
		synchronized (ownNode.getChain()) {
			//Create the transaction
			TransactionCreator creator = new TransactionCreator(localStore, receiver, amount);
			Transaction transaction = creator.createTransaction();
			
			//Create the block
			//TODO how many transactions do we put in one block?
			List<Transaction> transactions = new ArrayList<>();
			transactions.add(transaction);
			
			//Add block to local chain
			Block oldLastBlock = ownNode.getChain().getLastBlock();
			Block newBlock = new Block(oldLastBlock.getNumber() + 1, oldLastBlock, ownNode, transactions);
			ownNode.getChain().getBlocks().add(newBlock);
		}
		
		//Check if we want to commit the new block, and commit it if we do.
		commitBlocks(localStore);
	}
	
	/**
	 * Commits blocks to the main chain if necessary.
	 * @param localStore - the local store
	 * @throws InterruptedException - if sending transactions is interrupted
	 */
	public void commitBlocks(LocalStore localStore) throws InterruptedException {
		Block lastBlock, lastCommitted;
		Chain ownChain = localStore.getOwnNode().getChain();
		synchronized (ownChain) {
			lastBlock = ownChain.getLastBlock();
			lastCommitted = ownChain.getLastCommittedBlock();
			//TODO The last committed block should never be null (should be at least the genesis block)
			
			if (!shouldCommitBlocks(lastBlock.getNumber() - lastCommitted.getNumber())) return;
			
			//Commit to main chain
			BlockAbstract blockAbstract = lastBlock.getBlockAbstract();
			localStore.getApplication().getMainChain().commitAbstract(blockAbstract);
			ownChain.setLastCommittedBlock(lastBlock);
		}
		
		//Actually send the transactions in the blocks
		sendTransactions(localStore, lastBlock, lastCommitted);
	}
	
	/**
	 * Sends transactions of the blocks from {@code from} to {@code to} (exclusive).
	 * Sending of transactions happens in backwards order.
	 * The block number of {@code from} has to be larger than or equal to the block number of {@code to}.
	 * @param localStore - the local store
	 * @param from       - the (latest) block to send transactions of
	 * @param to         - the (last commited) block
	 * @throws InterruptedException - if sending transactions is interrupted
	 */
	public void sendTransactions(LocalStore localStore, Block from, Block to) throws InterruptedException {
		while (from != to) {
			for (Transaction transaction : from.getTransactions()) {
				localStore.getApplication().sendTransaction(transaction);
			}
			
			from = from.getPreviousBlock();
		}
	}
	
	private void writeObject(ObjectOutputStream stream) throws IOException {
		stream.defaultWriteObject();
	}

	private void readObject(ObjectInputStream stream) throws IOException, ClassNotFoundException {
		stream.defaultReadObject();
		this.random = new Random();
		if (this.seed != null) {
			this.random.setSeed(this.seed);
		}
	}
}