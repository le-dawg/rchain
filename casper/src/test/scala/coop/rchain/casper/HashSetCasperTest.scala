package coop.rchain.casper

import java.nio.file.Files

import cats.Id
import cats.data.EitherT
import cats.implicits._
import com.google.protobuf.ByteString
import coop.rchain.blockstorage.BlockStore
import coop.rchain.casper.genesis.Genesis
import coop.rchain.casper.genesis.contracts._
import coop.rchain.casper.helper.{BlockStoreTestFixture, BlockUtil, HashSetCasperTestNode}
import coop.rchain.casper.protocol._
import coop.rchain.casper.util.{BondingUtil, ProtoUtil}
import coop.rchain.casper.util.ProtoUtil.{chooseNonConflicting, signBlock, toJustification}
import coop.rchain.casper.util.rholang.RuntimeManager
import coop.rchain.casper.util.rholang.InterpreterUtil.mkTerm
import coop.rchain.catscontrib.TaskContrib.TaskOps
import coop.rchain.comm.rp.ProtocolHelper.packet
import coop.rchain.comm.transport
import coop.rchain.crypto.codec.Base16
import coop.rchain.crypto.hash.{Blake2b256, Keccak256}
import coop.rchain.crypto.signatures.{Ed25519, Secp256k1}
import coop.rchain.rholang.interpreter.{accounting, Runtime}
import coop.rchain.shared.PathOps.RichPath
import monix.eval.Task
import monix.execution.Scheduler
import monix.execution.Scheduler.Implicits.global
import org.scalatest.{FlatSpec, Matchers}

import scala.collection.immutable
import scala.util.Random

class HashSetCasperTest extends FlatSpec with Matchers {

  import HashSetCasperTest._

  private val (otherSk, otherPk)          = Ed25519.newKeyPair
  private val (validatorKeys, validators) = (1 to 4).map(_ => Ed25519.newKeyPair).unzip
  private val (ethPivKeys, ethPubKeys)    = (1 to 4).map(_ => Secp256k1.newKeyPair).unzip
  private val ethAddresses =
    ethPubKeys.map(pk => "0x" + Base16.encode(Keccak256.hash(pk.bytes.drop(1)).takeRight(20)))
  private val wallets     = ethAddresses.map(addr => PreWallet(addr, BigInt(10001)))
  private val bonds       = createBonds(validators)
  private val minimumBond = 100L
  private val genesis =
    buildGenesis(wallets, bonds, minimumBond, Long.MaxValue, Faucet.basicWalletFaucet, 0L)

  //put a new casper instance at the start of each
  //test since we cannot reset it
  "HashSetCasper" should "accept deploys" in {
    val node = HashSetCasperTestNode.standalone(genesis, validatorKeys.head)
    import node._

    val deploy = ProtoUtil.basicDeployData(0)
    MultiParentCasper[Id].deploy(deploy)

    logEff.infos.size should be(1)
    logEff.infos.head.contains("Received Deploy") should be(true)
    node.tearDown()
  }

  it should "not allow multiple threads to process the same block" in {
    val scheduler = Scheduler.fixedPool("three-threads", 3)
    val node      = HashSetCasperTestNode.standaloneEff(genesis, validatorKeys.head)(scheduler)
    val casper    = node.casperEff

    val deploy = ProtoUtil.basicDeployData(0)
    val testProgram = for {
      _     <- casper.deploy(deploy)
      block <- casper.createBlock.map { case Created(block) => block }
      result <- EitherT(
                 Task.racePair(casper.addBlock(block).value, casper.addBlock(block).value).flatMap {
                   case Left((statusA, running)) =>
                     running.join.map((statusA, _).tupled)

                   case Right((running, statusB)) =>
                     running.join.map((_, statusB).tupled)
                 }
               )
    } yield result
    val threadStatuses: (BlockStatus, BlockStatus) =
      testProgram.value.unsafeRunSync(scheduler).right.get

    threadStatuses should matchPattern { case (Processing, Valid) | (Valid, Processing) => }
    node.tearDown()
  }

  it should "create blocks based on deploys" in {
    val node            = HashSetCasperTestNode.standalone(genesis, validatorKeys.head)
    implicit val casper = node.casperEff

    val deploy = ProtoUtil.basicDeployData(0)
    MultiParentCasper[Id].deploy(deploy)

    val Created(block) = MultiParentCasper[Id].createBlock
    val deploys        = block.body.get.deploys.flatMap(_.deploy)
    val parents        = ProtoUtil.parentHashes(block)
    val storage        = blockTuplespaceContents(block)

    parents.size should be(1)
    parents.head should be(genesis.blockHash)
    deploys.size should be(1)
    deploys.head.raw should be(Some(deploy))
    storage.contains("@{0}!(0)") should be(true)
    node.tearDown()
  }

  it should "accept signed blocks" in {
    val node = HashSetCasperTestNode.standalone(genesis, validatorKeys.head)
    import node._

    val deploy = ProtoUtil.basicDeployData(0)
    MultiParentCasper[Id].deploy(deploy)

    val Created(signedBlock) = MultiParentCasper[Id].createBlock

    MultiParentCasper[Id].addBlock(signedBlock)

    val logMessages = List(
      "Received Deploy",
      "Sent Block #1",
      "Added",
      "New fork-choice tip is block"
    )

    logEff.warns.isEmpty should be(true)
    logEff.infos.zip(logMessages).forall { case (a, b) => a.startsWith(b) } should be(true)
    val dag = MultiParentCasper[Id].blockDag
    MultiParentCasper[Id].estimator(dag) should be(IndexedSeq(signedBlock))
    node.tearDown()
  }

  it should "be able to use the registry" in {
    val node = HashSetCasperTestNode.standalone(genesis, validatorKeys.head)
    import node.casperEff

    def now = System.currentTimeMillis()
    val registerDeploy = ProtoUtil.sourceDeploy(
      """new rr(`rho:registry:insertArbitrary`), hello, uriCh in {
        |  contract hello(@name, return) = { return!("Hello, ${name}!" %% {"name" : name}) } |
        |  rr!(bundle+{*hello}, *uriCh)
        |}
      """.stripMargin,
      now,
      accounting.MAX_VALUE
    )

    casperEff.deploy(registerDeploy)
    val Created(block) = casperEff.createBlock
    val blockStatus    = casperEff.addBlock(block)

    val id: String = casperEff
      .storageContents(block.getBody.getPostState.tuplespace)
      .split('|')
      .find(_.contains("rho:id"))
      .get
      .split('`')(1)
    val callDeploy = ProtoUtil.sourceDeploy(
      s"""new rl(`rho:registry:lookup`), helloCh, out in {
         |  rl!(`$id`, *helloCh) |
         |  for(hello <- helloCh){ hello!("World", *out) }
         |}
      """.stripMargin,
      now,
      accounting.MAX_VALUE
    )
    casperEff.deploy(callDeploy)
    val Created(block2) = casperEff.createBlock
    val block2Status    = casperEff.addBlock(block2)

    blockStatus shouldBe Valid
    block2Status shouldBe Valid
    casperEff
      .storageContents(block2.getBody.getPostState.tuplespace)
      .contains("Hello, World!") shouldBe true

    node.tearDown()
  }

  it should "be able to create a chain of blocks from different deploys" in {
    val node = HashSetCasperTestNode.standalone(genesis, validatorKeys.head)
    import node._

    val start = System.currentTimeMillis()

    val deployDatas = Vector(
      "contract @\"add\"(@x, @y, ret) = { ret!(x + y) }",
      "new unforgable in { @\"add\"!(5, 7, *unforgable) }"
    ).zipWithIndex.map(s => ProtoUtil.sourceDeploy(s._1, start + s._2, accounting.MAX_VALUE))

    val Created(signedBlock1) = MultiParentCasper[Id].deploy(deployDatas.head) *> MultiParentCasper[
      Id
    ].createBlock
    MultiParentCasper[Id].addBlock(signedBlock1)

    val Created(signedBlock2) = MultiParentCasper[Id].deploy(deployDatas(1)) *> MultiParentCasper[
      Id
    ].createBlock
    MultiParentCasper[Id].addBlock(signedBlock2)
    val storage = blockTuplespaceContents(signedBlock2)

    logEff.warns should be(Nil)
    ProtoUtil.parentHashes(signedBlock2) should be(Seq(signedBlock1.blockHash))
    val dag = MultiParentCasper[Id].blockDag
    MultiParentCasper[Id].estimator(dag) should be(IndexedSeq(signedBlock2))
    storage.contains("!(12)") should be(true)
    node.tearDown()
  }

  it should "allow multiple deploys in a single block" in {
    val node = HashSetCasperTestNode.standalone(genesis, validatorKeys.head)
    import node._

    val startTime = System.currentTimeMillis()
    val source    = " for(@x <- @0){ @0!(x) } | @0!(0) "
    val deploys = (source #:: source #:: Stream.empty[String]).zipWithIndex
      .map(s => ProtoUtil.sourceDeploy(s._1, startTime + s._2, accounting.MAX_VALUE))
    deploys.foreach(MultiParentCasper[Id].deploy(_))
    val Created(block) = MultiParentCasper[Id].createBlock
    val _              = MultiParentCasper[Id].addBlock(block)

    MultiParentCasper[Id].contains(block) shouldBe true
    node.tearDown()
  }

  it should "reject unsigned blocks" in {
    val node = HashSetCasperTestNode.standalone(genesis, validatorKeys.head)
    import node._

    val Created(block) = MultiParentCasper[Id].deploy(ProtoUtil.basicDeployData(0)) *> MultiParentCasper[
      Id
    ].createBlock
    val invalidBlock = block.withSig(ByteString.EMPTY)

    MultiParentCasper[Id].addBlock(invalidBlock)

    logEff.warns.head.contains("Ignoring block") should be(true)
    node.tearDownNode()
    validateBlockStore(node) { blockStore =>
      blockStore.get(block.blockHash) shouldBe None
    }
  }

  it should "reject blocks not from bonded validators" in {
    val node = HashSetCasperTestNode.standalone(genesis, otherSk)
    import node._

    val Created(signedBlock) = MultiParentCasper[Id].deploy(ProtoUtil.basicDeployData(0)) *> MultiParentCasper[
      Id
    ].createBlock

    MultiParentCasper[Id].addBlock(signedBlock)

    logEff.warns.head.contains("Ignoring block") should be(true)
    node.tearDownNode()
    validateBlockStore(node) { blockStore =>
      blockStore.get(signedBlock.blockHash) shouldBe None
    }
  }

  it should "propose blocks it adds to peers" in {
    val nodes      = HashSetCasperTestNode.network(validatorKeys.take(2), genesis)
    val deployData = ProtoUtil.basicDeployData(0)

    val Created(signedBlock) = nodes(0).casperEff
      .deploy(deployData) *> nodes(0).casperEff.createBlock

    nodes(0).casperEff.addBlock(signedBlock)
    nodes(1).receive()

    val received = nodes(1).casperEff.contains(signedBlock)

    received should be(true)

    nodes.foreach(_.tearDownNode())
    nodes.foreach { node =>
      validateBlockStore(node) { blockStore =>
        blockStore.get(signedBlock.blockHash) shouldBe Some(signedBlock)
      }
    }
  }

  it should "add a valid block from peer" in {
    val nodes      = HashSetCasperTestNode.network(validatorKeys.take(2), genesis)
    val deployData = ProtoUtil.basicDeployData(1)

    val Created(signedBlock1Prime) = nodes(0).casperEff
      .deploy(deployData) *> nodes(0).casperEff.createBlock

    nodes(0).casperEff.addBlock(signedBlock1Prime)
    nodes(1).receive()

    nodes(1).logEff.infos.count(_ startsWith "Added") should be(1)
    nodes(1).logEff.warns.count(_ startsWith "Recording invalid block") should be(0)

    nodes.foreach(_.tearDownNode())
    nodes.foreach { node =>
      validateBlockStore(node) { blockStore =>
        blockStore.get(signedBlock1Prime.blockHash) shouldBe Some(signedBlock1Prime)
      }
    }
  }

  it should "handle multi-parent blocks correctly" in {
    val nodes = HashSetCasperTestNode.network(validatorKeys.take(2), genesis)
    val deploys = Vector(
      ProtoUtil.basicDeployData(0),
      ProtoUtil.sourceDeploy(
        "@1!(1) | for(@x <- @1){ @1!(x) }",
        System.currentTimeMillis(),
        accounting.MAX_VALUE
      ),
      ProtoUtil.basicDeployData(2)
    )

    val Created(block0) = nodes(0).casperEff.deploy(deploys(0)) *> nodes(0).casperEff.createBlock
    val Created(block1) = nodes(1).casperEff.deploy(deploys(1)) *> nodes(1).casperEff.createBlock
    nodes(0).casperEff.addBlock(block0)
    nodes(1).casperEff.addBlock(block1)
    nodes(0).receive()
    nodes(1).receive()
    nodes(0).receive()
    nodes(1).receive()

    //multiparent block joining block0 and block1 since they do not conflict
    val Created(multiparentBlock) = nodes(0).casperEff
      .deploy(deploys(2)) *> nodes(0).casperEff.createBlock
    nodes(0).casperEff.addBlock(multiparentBlock)
    nodes(1).receive()

    nodes(0).logEff.warns.isEmpty shouldBe true
    nodes(1).logEff.warns.isEmpty shouldBe true
    multiparentBlock.header.get.parentsHashList.size shouldBe 2
    nodes(0).casperEff.contains(multiparentBlock) shouldBe true
    nodes(1).casperEff.contains(multiparentBlock) shouldBe true

    val finalTuplespace =
      nodes(0).casperEff.storageContents(multiparentBlock.getBody.getPostState.tuplespace)
    finalTuplespace.contains("@{0}!(0)") shouldBe true
    finalTuplespace.contains("@{1}!(1)") shouldBe true
    finalTuplespace.contains("@{2}!(2)") shouldBe true

    nodes.foreach(_.tearDown())
  }

  it should "allow bonding and distribute the joining fee" in {
    val nodes =
      HashSetCasperTestNode.network(
        validatorKeys :+ otherSk,
        genesis,
        storageSize = 1024L * 1024 * 10
      )
    implicit val runtimeManager = nodes(0).runtimeManager
    val pubKey                  = Base16.encode(ethPubKeys.head.bytes.drop(1))
    val secKey                  = ethPivKeys.head.bytes
    val ethAddress              = ethAddresses.head
    val bondKey                 = Base16.encode(otherPk)
    val walletUnlockDeploy =
      RevIssuanceTest.preWalletUnlockDeploy(ethAddress, pubKey, secKey, "unlockOut")
    val bondingForwarderAddress = BondingUtil.bondingForwarderAddress(ethAddress)
    val bondingForwarderDeploy = ProtoUtil.sourceDeploy(
      BondingUtil.bondingForwarderDeploy(bondKey, ethAddress),
      System.currentTimeMillis(),
      accounting.MAX_VALUE
    )
    val transferStatusOut = BondingUtil.transferStatusOut(ethAddress)
    val bondingTransferDeploy =
      RevIssuanceTest.walletTransferDeploy(
        0,
        wallets.head.initRevBalance.toLong,
        bondingForwarderAddress,
        transferStatusOut,
        pubKey,
        secKey
      )

    val Created(block1) = nodes(0).casperEff.deploy(walletUnlockDeploy) *> nodes(0).casperEff
      .deploy(bondingForwarderDeploy) *> nodes(0).casperEff.createBlock
    val block1Status = nodes(0).casperEff.addBlock(block1)
    nodes.foreach(_.receive) //send to all peers

    val Created(block2) = nodes(1).casperEff
      .deploy(bondingTransferDeploy) *> nodes(1).casperEff.createBlock
    val block2Status = nodes(1).casperEff.addBlock(block2)
    nodes.foreach(_.receive)

    val helloWorldDeploy = ProtoUtil.sourceDeploy(
      """new s(`rho:io:stdout`) in { s!("Hello, World!") }""",
      System.currentTimeMillis(),
      accounting.MAX_VALUE
    )
    //new validator does deploy/propose
    val Created(block3) = nodes.last.casperEff
      .deploy(helloWorldDeploy) *> nodes.last.casperEff.createBlock
    val block3Status = nodes.last.casperEff.addBlock(block3)

    //previous validator does deploy/propose
    val Created(block3Prime) = nodes.head.casperEff
      .deploy(helloWorldDeploy) *> nodes.head.casperEff.createBlock
    val block3PrimeStatus = nodes.head.casperEff.addBlock(block3Prime)

    nodes.foreach(_.receive) //all nodes get the blocks

    block1Status shouldBe Valid
    block2Status shouldBe Valid
    block3Status shouldBe Valid
    block3PrimeStatus shouldBe Valid
    nodes.forall(_.logEff.warns.isEmpty) shouldBe true

    val rankedValidatorQuery =
      mkTerm("""for(@pos <- @"proofOfStake"){ 
    |  new bondsCh, getRanking in {
    |    contract getRanking(@bonds, @acc, return) = {
    |      match bonds {
    |        {key:(stake, _, _, index) ...rest} => {
    |          getRanking!(rest, acc ++ [(key, stake, index)], *return)
    |        }
    |        _ => { return!(acc) }
    |      }
    |    } |
    |    @(pos, "getBonds")!(*bondsCh) | for(@bonds <- bondsCh) {
    |      getRanking!(bonds, [], "__SCALA__")
    |    }
    |  }
    |}""".stripMargin).right.get
    val validatorBondsAndRanks: Seq[(ByteString, Long, Int)] = runtimeManager
      .captureResults(block1.getBody.getPostState.tuplespace, rankedValidatorQuery)
      .head
      .exprs
      .head
      .getEListBody
      .ps
      .map(
        _.exprs.head.getETupleBody.ps match {
          case Seq(a, b, c) =>
            (a.exprs.head.getGByteArray, b.exprs.head.getGInt, c.exprs.head.getGInt.toInt)
        }
      )

    val joiningFee = minimumBond
    val n          = validatorBondsAndRanks.size
    val joiningFeeDistribution = (1 to n).map { k =>
      k -> ((2L * minimumBond * (n + 1 - k)) / (n * (n + 1)))
    }.toMap
    val total = joiningFeeDistribution.values.sum
    val finalFeesDist =
      joiningFeeDistribution.updated(1, joiningFeeDistribution(1) + joiningFee - total)
    val correctBonds = validatorBondsAndRanks.map {
      case (key, stake, rank) =>
        Bond(key, stake + finalFeesDist(rank))
    }.toSet + Bond(
      ByteString.copyFrom(otherPk),
      wallets.head.initRevBalance.toLong - joiningFee
    )

    val newBonds = block2.getBody.getPostState.bonds
    newBonds.toSet shouldBe correctBonds

    nodes.foreach(_.tearDown())
  }

  it should "have a working faucet (in testnet)" in {
    val node = HashSetCasperTestNode.standalone(genesis, validatorKeys.head)
    import node.casperEff

    val (_, pk) = Ed25519.newKeyPair
    val pkStr   = Base16.encode(pk)
    val amount  = 157L
    val createWalletCode =
      s"""for(faucet <- @"faucet"){ faucet!($amount, "ed25519", "$pkStr", "myWallet") }"""
    val createWalletDeploy =
      ProtoUtil.sourceDeploy(createWalletCode, System.currentTimeMillis(), accounting.MAX_VALUE)

    val Created(block) = casperEff.deploy(createWalletDeploy) *> casperEff.createBlock
    val blockStatus    = casperEff.addBlock(block)

    val balanceQuery =
      mkTerm("""for(@[wallet] <- @"myWallet"){ @(wallet, "getBalance")!("__SCALA__") }""").right.get
    val newWalletBalance =
      node.runtimeManager.captureResults(block.getBody.getPostState.tuplespace, balanceQuery)

    blockStatus shouldBe Valid
    newWalletBalance.head.exprs.head.getGInt shouldBe amount

    node.tearDown()
  }

  it should "allow bonding via the faucet" in {
    val node = HashSetCasperTestNode.standalone(genesis, validatorKeys.head)
    import node.casperEff
    import coop.rchain.catscontrib.effect.implicits._

    implicit val runtimeManager = node.runtimeManager
    val (sk, pk)                = Ed25519.newKeyPair
    val pkStr                   = Base16.encode(pk)
    val amount                  = 314L
    val forwardCode             = BondingUtil.bondingForwarderDeploy(pkStr, pkStr)
    val bondingCode             = BondingUtil.faucetBondDeploy[Id](amount, "ed25519", pkStr, sk)

    val forwardDeploy =
      ProtoUtil.sourceDeploy(forwardCode, System.currentTimeMillis(), accounting.MAX_VALUE)
    val bondingDeploy =
      ProtoUtil.sourceDeploy(bondingCode, forwardDeploy.timestamp + 1, accounting.MAX_VALUE)
    val Created(block1) = casperEff.deploy(forwardDeploy) *> casperEff.createBlock
    val block1Status    = casperEff.addBlock(block1)
    val Created(block2) = casperEff.deploy(bondingDeploy) *> casperEff.createBlock
    val block2Status    = casperEff.addBlock(block2)

    val oldBonds = block1.getBody.getPostState.bonds
    val newBonds = block2.getBody.getPostState.bonds

    block1Status shouldBe Valid
    block2Status shouldBe Valid
    (oldBonds.size + 1) shouldBe newBonds.size

    node.tearDown()
  }

  it should "reject addBlock when there exist deploy by the same (user, millisecond timestamp) in the chain" in {
    val nodes = HashSetCasperTestNode.network(validatorKeys.take(2), genesis)

    val deployDatas = (0 to 2).map(i => ProtoUtil.basicDeployData(i))
    val deployPrim0 = deployDatas(1)
      .withTimestamp(deployDatas(0).timestamp)
      .withUser(deployDatas(0).user) // deployPrim0 has the same (user, millisecond timestamp) with deployDatas(0)

    val Created(signedBlock1) = nodes(0).casperEff
      .deploy(deployDatas(0)) *> nodes(0).casperEff.createBlock
    nodes(0).casperEff.addBlock(signedBlock1)
    nodes(1).receive() // receive block1

    val Created(signedBlock2) = nodes(0).casperEff
      .deploy(deployDatas(1)) *> nodes(0).casperEff.createBlock
    nodes(0).casperEff.addBlock(signedBlock2)
    nodes(1).receive() // receive block2

    val Created(signedBlock3) = nodes(0).casperEff
      .deploy(deployDatas(2)) *> nodes(0).casperEff.createBlock
    nodes(0).casperEff.addBlock(signedBlock3)
    nodes(1).receive() // receive block3

    nodes(1).casperEff.contains(signedBlock3) should be(true)

    val Created(signedBlock4) = nodes(1).casperEff
      .deploy(deployPrim0) *> nodes(1).casperEff.createBlock
    nodes(1).casperEff.addBlock(signedBlock4) // should succeed
    nodes(0).receive()                        // still receive signedBlock4

    nodes(1).casperEff.contains(signedBlock4) should be(true) // Invalid blocks are still added
    nodes(0).casperEff.contains(signedBlock4) should be(true)

    nodes(0).logEff.warns
      .count(_ contains "found deploy by the same (user, millisecond timestamp) produced") should be(
      1
    )
    nodes.foreach(_.tearDownNode())

    nodes.foreach { node =>
      validateBlockStore(node) { blockStore =>
        blockStore.get(signedBlock1.blockHash) shouldBe Some(signedBlock1)
        blockStore.get(signedBlock2.blockHash) shouldBe Some(signedBlock2)
        blockStore.get(signedBlock3.blockHash) shouldBe Some(signedBlock3)
      }
    }
  }

  it should "ask peers for blocks it is missing" in {
    val nodes = HashSetCasperTestNode.network(validatorKeys.take(3), genesis)
    val deployDatas = Vector(
      "for(_ <- @1){ Nil } | @1!(1)",
      "@2!(2)"
    ).zipWithIndex
      .map(
        d => ProtoUtil.sourceDeploy(d._1, System.currentTimeMillis() + d._2, accounting.MAX_VALUE)
      )

    val Created(signedBlock1) = nodes(0).casperEff
      .deploy(deployDatas(0)) *> nodes(0).casperEff.createBlock

    nodes(0).casperEff.addBlock(signedBlock1)
    nodes(1).receive()
    nodes(2).transportLayerEff.clear(nodes(2).local) //nodes(2) misses this block

    val Created(signedBlock2) = nodes(0).casperEff
      .deploy(deployDatas(1)) *> nodes(0).casperEff.createBlock

    nodes(0).casperEff.addBlock(signedBlock2)
    nodes(1).receive() //receives block2
    nodes(2).receive() //receives block2; asks for block1
    nodes(1).receive() //receives request for block1; sends block1
    nodes(2).receive() //receives block1; adds both block1 and block2

    nodes(2).casperEff.contains(signedBlock1) should be(true)
    nodes(2).casperEff.contains(signedBlock2) should be(true)

    nodes(2).logEff.infos
      .count(_ startsWith "Requested missing block") should be(1)
    nodes(1).logEff.infos.count(
      s => (s startsWith "Received request for block") && (s endsWith "Response sent.")
    ) should be(1)

    nodes.foreach(_.tearDownNode())
    nodes.foreach { node =>
      validateBlockStore(node) { blockStore =>
        blockStore.get(signedBlock1.blockHash) shouldBe Some(signedBlock1)
        blockStore.get(signedBlock2.blockHash) shouldBe Some(signedBlock2)
      }
    }
  }

  it should "ignore adding equivocation blocks" in {
    val nodes = HashSetCasperTestNode.network(validatorKeys.take(2), genesis)

    // Creates a pair that constitutes equivocation blocks
    val Created(signedBlock1) = nodes(0).casperEff
      .deploy(ProtoUtil.basicDeployData(0)) *> nodes(0).casperEff.createBlock
    val Created(signedBlock1Prime) = nodes(0).casperEff
      .deploy(ProtoUtil.basicDeployData(1)) *> nodes(0).casperEff.createBlock

    nodes(0).casperEff.addBlock(signedBlock1)
    nodes(1).receive()
    nodes(0).casperEff.addBlock(signedBlock1Prime)
    nodes(1).receive()

    nodes(1).casperEff.contains(signedBlock1) should be(true)
    nodes(1).casperEff
      .contains(signedBlock1Prime) should be(false) // we still add the equivocation pair

    nodes(0).tearDownNode()
    nodes(1).tearDownNode()
    validateBlockStore(nodes(1)) { blockStore =>
      blockStore.get(signedBlock1.blockHash) shouldBe Some(signedBlock1)
      blockStore.get(signedBlock1Prime.blockHash) shouldBe None
    }
  }

  // See [[/docs/casper/images/minimal_equivocation_neglect.png]] but cross out genesis block
  it should "not ignore equivocation blocks that are required for parents of proper nodes" in {
    val nodes       = HashSetCasperTestNode.network(validatorKeys.take(4), genesis)
    val deployDatas = (0 to 5).map(i => ProtoUtil.basicDeployData(i))

    // Creates a pair that constitutes equivocation blocks
    val Created(signedBlock1) = nodes(0).casperEff
      .deploy(deployDatas(0)) *> nodes(0).casperEff.createBlock
    val Created(signedBlock1Prime) = nodes(0).casperEff
      .deploy(deployDatas(1)) *> nodes(0).casperEff.createBlock

    nodes(1).casperEff.addBlock(signedBlock1)
    nodes(3).receive()                               //node(3) receive block1
    nodes(0).transportLayerEff.clear(nodes(0).local) //nodes(0) misses this block
    nodes(2).transportLayerEff.clear(nodes(2).local) //nodes(2) misses this block

    nodes(0).casperEff.addBlock(signedBlock1Prime)
    nodes(2).receive()
    nodes(1).transportLayerEff.clear(nodes(1).local) //nodes(1) misses this block
    nodes(3).transportLayerEff.clear(nodes(3).local) //nodes(3) misses this block

    nodes(1).casperEff.contains(signedBlock1) should be(true)
    nodes(3).casperEff.contains(signedBlock1) should be(true)
    nodes(2).casperEff.contains(signedBlock1) should be(false)

    nodes(1).casperEff.contains(signedBlock1Prime) should be(false)
    nodes(2).casperEff.contains(signedBlock1Prime) should be(true)
    nodes(3).casperEff.contains(signedBlock1Prime) should be(false)

    val Created(signedBlock2) = nodes(1).casperEff
      .deploy(deployDatas(2)) *> nodes(1).casperEff.createBlock
    val Created(signedBlock3) = nodes(2).casperEff
      .deploy(deployDatas(3)) *> nodes(2).casperEff.createBlock

    nodes(2).casperEff.addBlock(signedBlock3)
    nodes(1).casperEff.addBlock(signedBlock2)
    nodes(2).transportLayerEff.clear(nodes(2).local) //nodes(2) ignores block2
    nodes(1).receive()                               // receives block3; asks for block1'
    nodes(2).receive()                               // receives request for block1'; sends block1'
    nodes(1).receive()                               // receives block1'; adds both block3 and block1'
    nodes(3).transportLayerEff.clear(nodes(3).local) // nodes(3) misses these blocks

    nodes(1).casperEff.contains(signedBlock3) should be(true)
    nodes(1).casperEff.contains(signedBlock1Prime) should be(true)

    val Created(signedBlock4) = nodes(1).casperEff
      .deploy(deployDatas(4)) *> nodes(1).casperEff.createBlock

    nodes(1).casperEff.addBlock(signedBlock4)
    nodes(3).receive() // receives block4, ask for block1', block2, block3
    nodes(1).receive() // receive request, send these blocks
    nodes(3).receive() // receives block1', block2 and block3, adds all these block

    // Node 1 should contain both blocks constituting the equivocation
    nodes(1).casperEff.contains(signedBlock1) should be(true)
    nodes(1).casperEff.contains(signedBlock1Prime) should be(true)

    nodes(1).casperEff.contains(signedBlock4) should be(true) // However, in invalidBlockTracker

    nodes(1).logEff.infos.count(_ startsWith "Added admissible equivocation") should be(1)
    nodes(3).logEff.warns.count(_ startsWith "Recording invalid block") should be(1)
    nodes(2).logEff.warns.size should be(0)
    nodes(1).logEff.warns.size should be(0)
    nodes(0).logEff.warns.size should be(0)

    nodes(1).casperEff.normalizedInitialFault(ProtoUtil.weightMap(genesis)) should be(
      1f / (1f + 3f + 5f + 7f)
    )
    nodes.foreach(_.tearDownNode())

    validateBlockStore(nodes(0)) { blockStore =>
      blockStore.get(signedBlock1.blockHash) shouldBe None
      blockStore.get(signedBlock1Prime.blockHash) shouldBe Some(signedBlock1Prime)
    }
    validateBlockStore(nodes(1)) { blockStore =>
      blockStore.get(signedBlock2.blockHash) shouldBe Some(signedBlock2)
      blockStore.get(signedBlock4.blockHash) shouldBe Some(signedBlock4)
    }
    validateBlockStore(nodes(2)) { blockStore =>
      blockStore.get(signedBlock3.blockHash) shouldBe Some(signedBlock3)
      blockStore.get(signedBlock1Prime.blockHash) shouldBe Some(signedBlock1Prime)
    }
  }

  it should "prepare to slash an block that includes a invalid block pointer" in {
    val nodes           = HashSetCasperTestNode.network(validatorKeys.take(3), genesis)
    val deploys         = (0 to 5).map(i => ProtoUtil.basicDeploy(i))
    val deploysWithCost = deploys.map(d => ProcessedDeploy(deploy = Some(d)))

    val Created(signedBlock) = nodes(0).casperEff
      .deploy(deploys(0).raw.get) *> nodes(0).casperEff.createBlock
    val signedInvalidBlock =
      BlockUtil.resignBlock(signedBlock.withSeqNum(-2), nodes(0).validatorId.privateKey) // Invalid seq num

    val blockWithInvalidJustification =
      buildBlockWithInvalidJustification(nodes, deploysWithCost, signedInvalidBlock)

    nodes(1).casperEff.addBlock(blockWithInvalidJustification)
    nodes(0).transportLayerEff
      .clear(nodes(0).local) // nodes(0) rejects normal adding process for blockThatPointsToInvalidBlock

    val signedInvalidBlockPacketMessage =
      packet(nodes(1).local, transport.BlockMessage, signedInvalidBlock.toByteString)
    nodes(0).transportLayerEff.send(nodes(1).local, signedInvalidBlockPacketMessage)
    nodes(1).receive() // receives signedInvalidBlock; attempts to add both blocks

    nodes(1).logEff.warns.count(_ startsWith "Recording invalid block") should be(1)
    nodes.foreach(_.tearDown())
  }

  it should "handle a long chain of block requests appropriately" in {
    val nodes =
      HashSetCasperTestNode.network(validatorKeys.take(2), genesis, storageSize = 1024L * 1024 * 10)

    (0 to 9).foreach { i =>
      val deploy         = ProtoUtil.basicDeployData(i)
      val Created(block) = nodes(0).casperEff.deploy(deploy) *> nodes(0).casperEff.createBlock

      nodes(0).casperEff.addBlock(block)
      nodes(1).transportLayerEff.clear(nodes(1).local) //nodes(1) misses this block
    }
    val Created(block) = nodes(0).casperEff
      .deploy(ProtoUtil.basicDeployData(10)) *> nodes(0).casperEff.createBlock
    nodes(0).casperEff.addBlock(block)

    (0 to 10).foreach { i =>
      nodes(1).receive()
      nodes(0).receive()
    }

    nodes(1).logEff.infos
      .count(_ startsWith "Requested missing block") should be(10)
    nodes(0).logEff.infos.count(
      s => (s startsWith "Received request for block") && (s endsWith "Response sent.")
    ) should be(10)

    nodes.foreach(_.tearDown())
  }

  it should "increment last finalized block as appropriate in round robin" in {
    val stake      = 10L
    val equalBonds = validators.map(_ -> stake).toMap
    val genesisWithEqualBonds =
      buildGenesis(Seq.empty, equalBonds, 1L, Long.MaxValue, Faucet.noopFaucet, 0L)
    val nodes       = HashSetCasperTestNode.network(validatorKeys.take(3), genesisWithEqualBonds)
    val deployDatas = (0 to 7).map(i => ProtoUtil.basicDeployData(i))

    val Created(block1) = nodes(0).casperEff
      .deploy(deployDatas(0)) *> nodes(0).casperEff.createBlock
    nodes(0).casperEff.addBlock(block1)
    nodes(1).receive()
    nodes(2).receive()

    val Created(block2) = nodes(1).casperEff
      .deploy(deployDatas(1)) *> nodes(1).casperEff.createBlock
    nodes(1).casperEff.addBlock(block2)
    nodes(0).receive()
    nodes(2).receive()

    val Created(block3) = nodes(2).casperEff
      .deploy(deployDatas(2)) *> nodes(2).casperEff.createBlock
    nodes(2).casperEff.addBlock(block3)
    nodes(0).receive()
    nodes(1).receive()

    val Created(block4) = nodes(0).casperEff
      .deploy(deployDatas(3)) *> nodes(0).casperEff.createBlock
    nodes(0).casperEff.addBlock(block4)
    nodes(1).receive()
    nodes(2).receive()

    val Created(block5) = nodes(1).casperEff
      .deploy(deployDatas(4)) *> nodes(1).casperEff.createBlock
    nodes(1).casperEff.addBlock(block5)
    nodes(0).receive()
    nodes(2).receive()

    nodes(0).casperEff.lastFinalizedBlock should be(genesisWithEqualBonds)

    val Created(block6) = nodes(2).casperEff
      .deploy(deployDatas(5)) *> nodes(2).casperEff.createBlock
    nodes(2).casperEff.addBlock(block6)
    nodes(0).receive()
    nodes(1).receive()

    nodes(0).casperEff.lastFinalizedBlock should be(block1)

    val Created(block7) = nodes(0).casperEff
      .deploy(deployDatas(6)) *> nodes(0).casperEff.createBlock
    nodes(0).casperEff.addBlock(block7)
    nodes(1).receive()
    nodes(2).receive()

    nodes(0).casperEff.lastFinalizedBlock should be(block2)

    val Created(block8) = nodes(1).casperEff
      .deploy(deployDatas(7)) *> nodes(1).casperEff.createBlock
    nodes(1).casperEff.addBlock(block8)
    nodes(0).receive()
    nodes(2).receive()

    nodes(0).casperEff.lastFinalizedBlock should be(block3)

    nodes.foreach(_.tearDown())
  }

  it should "fail when deploying with insufficient phlos" in {
    val node = HashSetCasperTestNode.standalone(genesis, validatorKeys.head)
    import node._

    val deployData = ProtoUtil.basicDeployData(0).withPhloLimit(PhloLimit(1))
    node.casperEff.deploy(deployData)

    val Created(block) = MultiParentCasper[Id].createBlock
    assert(block.body.get.deploys.head.errored)
  }

  it should "succeed if given enough phlos for deploy" in {
    val node = HashSetCasperTestNode.standalone(genesis, validatorKeys.head)
    import node._

    val deployData = ProtoUtil.basicDeployData(0).withPhloLimit(PhloLimit(100))
    node.casperEff.deploy(deployData)

    val Created(block) = MultiParentCasper[Id].createBlock
    assert(!block.body.get.deploys.head.errored)
  }

  private def buildBlockWithInvalidJustification(
      nodes: IndexedSeq[HashSetCasperTestNode[Id]],
      deploys: immutable.IndexedSeq[ProcessedDeploy],
      signedInvalidBlock: BlockMessage
  ) = {
    val postState     = RChainState().withBonds(ProtoUtil.bonds(genesis)).withBlockNumber(2)
    val postStateHash = Blake2b256.hash(postState.toByteArray)
    val header = Header()
      .withPostStateHash(ByteString.copyFrom(postStateHash))
      .withParentsHashList(Seq(signedInvalidBlock.blockHash))
      .withDeploysHash(ProtoUtil.protoSeqHash(deploys))
    val blockHash = Blake2b256.hash(header.toByteArray)
    val body      = Body().withPostState(postState).withDeploys(deploys)
    val serializedJustifications =
      Seq(Justification(signedInvalidBlock.sender, signedInvalidBlock.blockHash))
    val serializedBlockHash = ByteString.copyFrom(blockHash)
    val blockThatPointsToInvalidBlock =
      BlockMessage(serializedBlockHash, Some(header), Some(body), serializedJustifications)
    ProtoUtil.signBlock(
      blockThatPointsToInvalidBlock,
      nodes(1).casperEff.blockDag,
      validators(1),
      validatorKeys(1),
      "ed25519",
      "rchain"
    )
  }
}

object HashSetCasperTest {
  def validateBlockStore[R](node: HashSetCasperTestNode[Id])(f: BlockStore[Id] => R) = {
    val bs = BlockStoreTestFixture.create(node.dir)
    f(bs)
    bs.close()
    node.dir.recursivelyDelete()
  }

  def blockTuplespaceContents(
      block: BlockMessage
  )(implicit casper: MultiParentCasper[Id]): String = {
    val tsHash = block.body.get.postState.get.tuplespace
    MultiParentCasper[Id].storageContents(tsHash)
  }

  def createBonds(validators: Seq[Array[Byte]]): Map[Array[Byte], Long] =
    validators.zipWithIndex.map { case (v, i) => v -> (2L * i.toLong + 1L) }.toMap

  def createGenesis(bonds: Map[Array[Byte], Long]): BlockMessage =
    buildGenesis(Seq.empty, bonds, 1L, Long.MaxValue, Faucet.noopFaucet, 0L)

  def buildGenesis(
      wallets: Seq[PreWallet],
      bonds: Map[Array[Byte], Long],
      minimumBond: Long,
      maximumBond: Long,
      faucetCode: String => String,
      deployTimestamp: Long
  ): BlockMessage = {
    val initial           = Genesis.withoutContracts(bonds, 0L, deployTimestamp, "rchain")
    val storageDirectory  = Files.createTempDirectory(s"hash-set-casper-test-genesis")
    val storageSize: Long = 1024L * 1024
    val activeRuntime     = Runtime.create(storageDirectory, storageSize)
    val runtimeManager    = RuntimeManager.fromRuntime(activeRuntime)
    val emptyStateHash    = runtimeManager.emptyStateHash
    val validators        = bonds.map(bond => ProofOfStakeValidator(bond._1, bond._2)).toSeq
    val genesis = Genesis.withContracts(
      initial,
      ProofOfStakeParams(minimumBond, maximumBond, validators),
      wallets,
      faucetCode,
      emptyStateHash,
      runtimeManager,
      deployTimestamp
    )
    activeRuntime.close()
    genesis
  }
}
