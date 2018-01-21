package nl.tudelft.blockchain.scaleoutdistributedledger.model;

import lombok.Getter;
import lombok.Setter;
import nl.tudelft.blockchain.scaleoutdistributedledger.LocalStore;
import nl.tudelft.blockchain.scaleoutdistributedledger.message.BlockMessage;
import nl.tudelft.blockchain.scaleoutdistributedledger.message.TransactionMessage;
import nl.tudelft.blockchain.scaleoutdistributedledger.utils.Log;
import nl.tudelft.blockchain.scaleoutdistributedledger.utils.Utils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

/**
 * Block class.
 */
public class Block {

	public static final int GENESIS_BLOCK_NUMBER = 0;
	
	@Getter
	private final int number;

	@Getter
	private final Block previousBlock;

	@Getter @Setter
	private Node owner;

	@Getter
	private final List<Transaction> transactions;

	// Custom getter
	private Sha256Hash hash;
	
	private transient boolean onMainChain;
	private transient boolean hasNoAbstract;
	private transient volatile boolean finalized;

	/**
	 * Constructor for a (genesis) block.
	 * @param number - the number of this block.
	 * @param owner - the owner of this block.
	 * @param transactions - a list of transactions of this block.
	 */
	public Block(int number, Node owner, List<Transaction> transactions) {
		this.number = number;
		this.owner = owner;
		this.previousBlock = null;
		this.transactions = transactions;
		for (Transaction transaction : this.transactions) {
			transaction.setBlockNumber(number);
		}
	}
	
	/**
	 * Constructor for an empty block.
	 * @param previousBlock - reference to the previous block in the chain of this block.
	 * @param owner         - the owner
	 */
	public Block(Block previousBlock, Node owner) {
		this.number = previousBlock.getNumber() + 1;
		this.previousBlock = previousBlock;
		this.owner = owner;
		this.transactions = new ArrayList<>();
		
		//Our own blocks are guaranteed to have no abstract until we create the abstract.
		if (this.owner instanceof OwnNode) {
			this.hasNoAbstract = true;
		}
	}

	/**
	 * Constructor to decode a block message.
	 * @param blockMessage - block message from network.
	 * @param localStore - local store.
	 * @throws IOException - error while getting node from tracker.
	 */
	public Block(BlockMessage blockMessage, LocalStore localStore) throws IOException {
		this.number = blockMessage.getNumber();
		// It's a genesis block
		if (blockMessage.getOwnerId() == Transaction.GENESIS_SENDER) {
			this.owner = null;
		} else {
			this.owner = localStore.getNode(blockMessage.getOwnerId());
		}
		
		if (blockMessage.getPreviousBlock() != null) {
			// Convert BlockMessage to Block
			this.previousBlock = new Block(blockMessage.getPreviousBlock(), localStore);
		} else if (blockMessage.getPreviousBlockNumber() != -1) {
			// Get block by number from owner
			this.previousBlock = this.owner.getChain().getBlocks().get(blockMessage.getPreviousBlockNumber());
		} else {
			// It's a genesis block
			this.previousBlock = null;
		}
		
		// Convert TransactionMessage to Transaction
		this.transactions = new ArrayList<>();
		for (TransactionMessage transactionMessage : blockMessage.getTransactions()) {
			this.transactions.add(new Transaction(transactionMessage, localStore));
		}
		//TODO Do we want to send the hash along?
		this.hash = blockMessage.getHash();
	}
	
	/**
	 * Adds the given transaction to this block and sets its block number.
	 * @param transaction - the transaction to add
	 * @throws IllegalStateException - If this block has already been committed.
	 */
	public synchronized void addTransaction(Transaction transaction) {
		if (finalized) {
			throw new IllegalStateException("You cannot add transactions to a block that is already committed.");
		}
		
		transactions.add(transaction);
		transaction.setBlockNumber(this.getNumber());
	}
	
	/**
	 * Get hash of the block.
	 * @return Hash SHA256
	 */
	public synchronized Sha256Hash getHash() {
		if (this.hash == null) {
			this.hash = this.calculateHash();
		}
		return this.hash;
	}

	/**
	 * Calculate the abstract of the block.
	 * @return abstract of the block
	 * @throws IllegalStateException - something went wrong while signing the block
	 */
	public BlockAbstract calculateBlockAbstract() {
		if (!(this.owner instanceof OwnNode)) {
			throw new UnsupportedOperationException("You cannot calculate the block abstract of a block you do not own!");
		}
		
		// Convert attributes of abstract into an array of bytes, for the signature
		// Important to keep the order of writings
		byte[] attrInBytes;
		try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
			outputStream.write(Utils.intToByteArray(this.owner.getId()));
			outputStream.write(Utils.intToByteArray(this.number));
			outputStream.write(this.getHash().getBytes());
			attrInBytes = outputStream.toByteArray();
		} catch (IOException ex) {
			throw new IllegalStateException("Unable to write to outputstream", ex);
		}

		// Sign the attributes
		try {
			byte[] signature = ((OwnNode) this.owner).sign(attrInBytes);
			BlockAbstract blockAbstract = new BlockAbstract(this.owner.getId(), this.number, this.getHash(), signature);
			this.hasNoAbstract = false;
			return blockAbstract;
		} catch (Exception ex) {
			throw new IllegalStateException("Unable to sign block abstract", ex);
		}
	}
	
	/**
	 * Commits this block to the main chain.
	 * @param localStore - the local store
	 */
	public synchronized void commit(LocalStore localStore) {
		if (finalized) {
			throw new IllegalStateException("This block has already been committed!");
		}
		
		Chain chain = getOwner().getChain();
		synchronized (chain) {
			BlockAbstract blockAbstract = calculateBlockAbstract();
			localStore.getApplication().getMainChain().commitAbstract(blockAbstract);
			getOwner().getChain().setLastCommittedBlock(this);
		}
		
		finalized = true;
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + number;
		result = prime * result + (owner == null ? 0 : owner.hashCode());
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj) return true;
		if (!(obj instanceof Block)) return false;

		Block other = (Block) obj;
		if (this.number != other.number) return false;
		if (this.owner != other.owner) return false;

		if (this.previousBlock == null) {
			if (other.previousBlock != null) return false;
		} else if (!this.previousBlock.equals(other.previousBlock)) return false;

		return this.transactions.equals(other.transactions);
	}
	
	@Override
	public String toString() {
		return "Block<" + number + ", " + owner + ">";
	}

	/**
	 * Calculates the block hash.
	 * @return Hash SHA256
	 */
	private Sha256Hash calculateHash() {
		// Convert attributes of block into an array of bytes
		ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
		try {
			// Important to keep the order of writings
			outputStream.write(Utils.intToByteArray(this.number));
			byte[] prevBlockHash = (this.previousBlock != null) ? this.previousBlock.getHash().getBytes() : new byte[0];
			outputStream.write(prevBlockHash);
			if (this.owner != null) {
				outputStream.write(Utils.intToByteArray(this.owner.getId()));
			}
			for (Transaction tx : this.transactions) {
				outputStream.write(tx.getHash().getBytes());
			}
		} catch (IOException ex) {
			Log.log(Level.SEVERE, null, ex);
		}
		byte[] blockInBytes = outputStream.toByteArray();

		return new Sha256Hash(blockInBytes);
	}

	/**
	 * Creates a copy of this genesis block.
	 * @return - a deep copy of this block and transactions
	 * @throws UnsupportedOperationException - If this block is not a genesis block.
	 */
	public Block genesisCopy() {
		if (this.number != GENESIS_BLOCK_NUMBER) throw new UnsupportedOperationException("You can only copy genesis blocks");
		
		ArrayList<Transaction> transactionsCopy = new ArrayList<>();
		for (Transaction transaction : transactions) {
			transactionsCopy.add(transaction.genesisCopy());
		}
		Block block = new Block(this.number, this.owner, transactionsCopy);
		
		block.onMainChain = true;
		return block;
	}
	
	/**
	 * Returns if an abstract of this block is present on the main chain.
	 * @param localStore - the local store
	 * @return - boolean identifying if an abstract of this block is on the main chain.
	 */
	public boolean isOnMainChain(LocalStore localStore) {
		//TODO Remove hack?
		if (this.number == GENESIS_BLOCK_NUMBER) return true;
		
		//Definitely has no abstract
		if (this.hasNoAbstract) return false;
		
		//We already determined before what the result should be
		if (this.onMainChain) return true;
		
		//It is present, so store it and return
		if (localStore.getMainChain().isPresent(this.getHash())) {
			this.onMainChain = true;
			return true;
		}
		
		//Not present (yet)
		return false;
	}
}
