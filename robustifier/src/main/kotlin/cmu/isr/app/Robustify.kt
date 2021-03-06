package cmu.isr.app

import cmu.isr.dfa.parallelComposition
import cmu.isr.ltsa.LTSACall
import cmu.isr.ltsa.LTSACall.asDetLTS
import cmu.isr.ltsa.LTSACall.compose
import cmu.isr.robustify.desops.DESopsRunner
import cmu.isr.robustify.oasis.OASISRobustifier
import cmu.isr.robustify.supervisory.Algorithms
import cmu.isr.robustify.supervisory.Priority
import cmu.isr.robustify.supervisory.SupervisoryRobustifier
import cmu.isr.robustify.supremica.SupremicaRunner
import cmu.isr.utils.pretty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import net.automatalib.automata.fsa.impl.compact.CompactDFA
import net.automatalib.serialization.aut.AUTWriter
import net.automatalib.words.Word
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.config.Configurator
import org.slf4j.LoggerFactory
import java.io.File
import java.time.Duration

class Robustify : CliktCommand(help = "Robustify a system design using supervisory control.") {
  private val configFile by argument(name = "<config.json>")
  private val verbose by option("--verbose", "-v", help = "Enable verbose mode.").flag()

  private val logger = LoggerFactory.getLogger(javaClass)

  override fun run() {
    if (verbose) {
//      System.setProperty(org.slf4j.impl.SimpleLogger.DEFAULT_LOG_LEVEL_KEY, "Debug")
      Configurator.setAllLevels(LogManager.getRootLogger().name, Level.DEBUG)
    }

    val config = jacksonObjectMapper().readValue(File(configFile), RobustifyConfigJSON::class.java)
    val startTime = System.currentTimeMillis()

    when (config.method) {
      "supervisory" -> {
        val robustifer = buildSupervisory(config)
        robustifer.use {
          val sols = it.synthesize(Algorithms.valueOf(config.options.algorithm)).toList()
          logger.info("Total number of controller synthesis invoked: ${it.numberOfSynthesis}")
          logger.info("Total number of solutions: ${sols.size}")
          saveSolutions(sols)
        }
      }
      "oasis" -> {
        val robustifier = buildOASIS(config)
        val sol = if (config.options.controllable.isEmpty() || config.options.observable.isNotEmpty()) {
          robustifier.synthesize()
        } else {
          robustifier.synthesize(config.options.controllable, config.options.observable)
        }
        logger.info("Total number of controller synthesis invoked: ${robustifier.numberOfSynthesis}")
        if (sol != null) {
          saveSolutions(listOf(sol))
        } else {
          logger.warn("Failed to find a solution.")
        }
      }
      else -> error("Unsupported method, should be either 'supervisory' or 'oasis'.")
    }

    logger.info("Robustification completes, total time: ${Duration.ofMillis(System.currentTimeMillis() - startTime).pretty()}")
  }

  private fun parseSpecFile(path: String): CompactDFA<String> {
    val f = File(path)
    return when (f.extension) {
      "lts" -> LTSACall.compile(f.readText()).compose().asDetLTS()
      else -> error("Unsupported file type '.${f.extension}'")
    }
  }

  private fun parseSpecFiles(paths: List<String>): CompactDFA<String> {
    var c = parseSpecFile(paths[0])
    if (paths.size > 1) {
      for (i in 1 until paths.size) {
        val a = parseSpecFile(paths[i])
        c = parallelComposition(c, c.inputAlphabet, a, a.inputAlphabet)
      }
    }
    return c
  }

  private fun saveSolutions(dfas: List<CompactDFA<String>>) {
    val dir = File("./solutions")
    if (dir.exists())
      dir.deleteRecursively()
    dir.mkdir()
    for (i in dfas.indices) {
      val f = File("./solutions/sol${i+1}.aut")
      f.createNewFile()
      val out = f.outputStream()
      AUTWriter.writeAutomaton(dfas[i], dfas[i].inputAlphabet, out)
      out.close()
    }
  }

  private fun buildSupervisory(config: RobustifyConfigJSON): SupervisoryRobustifier {
    val sys = parseSpecFiles(config.sys)
    val dev = parseSpecFiles(config.dev)
    val safety = parseSpecFiles(config.safety)
    return SupervisoryRobustifier(
      sys, sys.inputAlphabet,
      dev, dev.inputAlphabet,
      safety, safety.inputAlphabet,
      progress = config.options.progress,
      preferredMap = config.options.preferredMap.map { entry ->
        when (entry.key) {
          "0" -> Priority.P0 to entry.value.map { Word.fromList(it) }
          "1" -> Priority.P1 to entry.value.map { Word.fromList(it) }
          "2" -> Priority.P2 to entry.value.map { Word.fromList(it) }
          "3" -> Priority.P3 to entry.value.map { Word.fromList(it) }
          else -> error("Unsupported priority $entry")
        }
      }.toMap(),
      controllableMap = config.options.controllableMap.map { entry ->
        when (entry.key) {
          "0" -> Priority.P0 to entry.value
          "1" -> Priority.P1 to entry.value
          "2" -> Priority.P2 to entry.value
          "3" -> Priority.P3 to entry.value
          else -> error("Unsupported priority $entry")
        }
      }.toMap(),
      observableMap = config.options.observableMap.map { entry ->
        when (entry.key) {
          "0" -> Priority.P0 to entry.value
          "1" -> Priority.P1 to entry.value
          "2" -> Priority.P2 to entry.value
          "3" -> Priority.P3 to entry.value
          else -> error("Unsupported priority $entry")
        }
      }.toMap(),
      synthesizer = when (SolverType.valueOf(config.options.solver)) {
        SolverType.Supremica -> SupremicaRunner()
        SolverType.DESops -> DESopsRunner { it }
      },
      maxIter = config.options.maxIter
    )
  }

  private fun buildOASIS(config: RobustifyConfigJSON): OASISRobustifier {
    val sys = parseSpecFiles(config.sys)
    val dev = parseSpecFiles(config.dev)
    val safety = parseSpecFiles(config.safety)
    return OASISRobustifier(
      sys, sys.inputAlphabet,
      dev, dev.inputAlphabet,
      safety, safety.inputAlphabet,
      progress = config.options.progress,
      preferred = config.options.preferred.map { Word.fromList(it) }
    )
  }
}