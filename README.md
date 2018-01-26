# ScaleOutDistributedLedger
TU Delft Blockchain Engineering course project on scale-out distributed ledger.

Technical report

# Data Model #
## Node ##

### Meta knowledge ###
We keep track of what each node knows. We call this `metaKnowlege`: what we know that they know. Each node contains a Map from nodes to integers, where the integer represents the last block that we know for sure that the receiver has. 

The meta knowledge is updated whenever we receive a transaction from that node and when we send a transaction to that node.

## Chain ##

## Block ##

## BlockAbstract ##

## Transaction ##

## Proof ##

### Smart proof collection ###
When sending a proof for a transaction, we use our metaKnowledge of the receiver to select the blocks that we send. We send only the part of the chain that we expect that the receiver doesn't have, up to the last committed block of that chain. Combined with Algorithm 3, this ensures that we send as little information as possible.

# The algorithms #
## Algorithm 1 ##
### TODO ###

## Algorithm 2 ##
### TODO ###

## Algorithm 3: Non-interactive Smart Transacting Algorithm ##
The algorithm in the paper basically requires the use of a powerset of all unspent transactions. Since this requires O(2^n) sets, this is only feasible for small numbers of n.

The algorithm implemented is smarter and eliminates bad combinations by keeping track of a best-so-far. It has the same worst case performance, O(2^n), e.g. if all unspent transactions are required for the transaction. If this is not the case however (you send a part of your money), then the performance of the implemented algorithm is much better. The less transactions are required as sources, the better this algorithm performs. The algorithm also benefits from sharding, as transactions with the same requirements (e.g. received from the same source) will be considered as a group instead of individually.

#### Step 1 ####
First we collect all unspent transactions (these are tracked in application). We then create a TransactionTuple for each transaction, which will calculate which chains would need to be sent based on what the receiver already knows.
We then merge the tuples that have the same chain requirements in O(|transactions|) by hashing them to buckets.

#### Step 2 ####
Check if there are single transactions / single groups that have a large enough amount to be used as the only source. If we find at least one of these, then all transactions that require more chains than the best single source are directly eliminated. (Combining those transactions with other transactions would only make the required set of chains bigger, so they will never be in the best choice)

We call the resulting set `candidates`.

If there are less than two candidates we stop and return the best-so-far.

#### Step 3 ####
We have a set of TransactionTuples `currentRound`, which is initially set to `candidates`.
We have a set of TransactionTuples `nextRound`, which is initially empty.

For at most `candidates.size() - 1` rounds, we do the following:
- We iterate over the candidates. We try to combine each candidate tuple with each element of `currentRound`.
    - If the combination requires more than or the same number of chains than the best-so-far, then it is eliminated.
    - Otherwise:
        - If it is able to cover the costs, we set it as best-so-far.
        - Otherwise, we add this combination to `nextRound`.
- If there are less than two combinations selected for the next round, then the algorithm returns the best-so-far.
- Otherwise, `currentRound := nextRound` and `nextRound := []` (empty).

### Result ###
The final combination of transactions returned is the set of sources with minimum amount of chains required. The set found is the largest possible set for its chain requirements, but not necessarily the largest possible set.

### Performance ###
The performance of the algorithm is still O(2^n) in the worst case. The worst case would be if all transactions are needed to get the amount of money required and none of them can be grouped. Then the algorithm will consider all possible combinations.

### Future optimizations ###
* It would probably be useful to keep track of how much money we have, to prevent the costly case where we don't have enough money.
* There could be 2 algorithms, one for when we expect many transactions will be required, one for when we expect only a few transactions will be required. The current algorithm works bottom up, the other algorithm could work top down. (How many transactions do we need at minimum to cover the transaction?)

### Implementation Details ###
BitSets are used as a convenient and efficient way to represent the chains that are required. They represent a sequence of bits of a particular size (e.g. 1000 bits).
* The bit at index 0 represents chain 0, the bit at index 1 represents chain 1, etc.
* A bit with a value of 1 means that the corresponding chain is required.
* Combining the chains required of 2 tuples is done with a bitwise or.
* The number of chains required is the cardinality of a bitset.
* Removing the chains that the receiver already knows is done with a bitwise andNot.

## Tracker Server
The tracker server can be installed by installing NodeJS and running `npm install` in the tracker-server folder. After this it can be run by running `npm start` in that same folder.

### Live demo view
A live demo view is available at http://localhost:3000/demo if the trackerserver is running. This demo view shows a live graph and counters for the total number of transactions and the average amount of blocks and chains sent per proof.

## Main Chain
For the main chain we use [Tendermint](https://tendermint.com/). We can communicate to this chain using the provided ABCI interface. 

### Running tendermint
Tendermint process can be spawned using TendermintHelper class, which takes care of everything that is needed to start it up.
The steps are usually:
1. Generate priv_validator.json file for each node.
2. Generate genesis block, which is then passed to calculate the initial application hash.
3. Generate genesis.json file for each node.
4. Run tendermint process for each of the nodes (this means there is a tendermint processes running at the same time, one for each node).

Alternatively, running tendermint can also be done with the following command: (the use of which is discouraged though)
`./tendermint node --consensus.create_empty_blocks=false` (Note that Tendermint creates empty blocks by default)
Note: For this method to work, you need to have a config.toml file, genesis.json and priv_validator.json for each node, and/or specify more parameters.

See the [tendermint documentation](https://tendermint.readthedocs.io/en/master/using-tendermint.html) for more information.
