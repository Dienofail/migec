/**
 Copyright 2013-2014 Mikhail Shugay (mikhail.shugay@gmail.com)

 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
 */

package com.milaboratory.migec

import groovyx.gpars.GParsPool

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

def R_A_T = "1.0"
def cli = new CliBuilder(usage:
        'FilterCdrBlastResults [options] inputAssembledResult inputRawResult outputResult')
cli.r(args: 1, argName: 'read accumulation threshold', "Only clonotypes that have a ratio of (reads after correction) / " +
        "(uncorrected reads) greater than that threshold are retained. Default: $R_A_T")
cli.s("Include clonotypes that are represented by single events (have only one associated MIG)")
cli.n("Include non-functional CDR3s")
cli.c("Include CDR3s that do not begin with a conserved C or end with a conserved W/F")
cli.p(args: 1, "number of threads to use. Default: all available processors")
cli._(longOpt: 'collapse', "Collapse clonotypes by CDR3 and use top V and J chains")
cli._(longOpt: 'log-file', args: 1, argName: 'file name', "File to output cdr extraction log")
cli._(longOpt: 'log-overwrite', "Overwrites provided log file")
cli._(longOpt: 'log-sample-name', "Sample name to use in log [default = N/A]")

def opt = cli.parse(args)
if (opt == null || opt.arguments().size() < 3) {
    println "[ERROR] Too few arguments provided"
    cli.usage()
    System.exit(-1)
}

int THREADS = opt.p ? Integer.parseInt(opt.p) : Runtime.getRuntime().availableProcessors()

double unitFilterRatio1mm = 20, unitFilterRatio2mm = unitFilterRatio1mm * unitFilterRatio1mm

boolean collapse = opt.'collapse'

int NT_SEQ_COL = 2, AA_SEQ_COL = 3, V_COL = 4, J_COL = 5, D_COL = 6,
    READ_COUNT_COL = 13, READ_TOTAL_COL = 14, EVENT_COUNT_COL = 11, EVENT_TOTAL_COL = 12,
    DATA_FROM = 2, DATA_TO = 14

def getCdrKey = { List<String> splitLine ->
    collapse ? splitLine[NT_SEQ_COL] : splitLine[[NT_SEQ_COL, V_COL, J_COL, D_COL]].join("\t")
}

def scriptName = getClass().canonicalName
def readAccumulationThreshold = Double.parseDouble(opt.r ?: R_A_T)
def filterUnits = !opt.s, filterNonFunctional = !opt.n, includeNonCanonical = opt.c
def asmInputFileName = opt.arguments()[0], rawInputFileName = opt.arguments()[1], outputFileName = opt.arguments()[2]

// LOGGING
String logFileName = opt.'log-file' ?: null
boolean overwriteLog = opt.'log-overwrite'
String sampleName = opt.'log-sample-name' ?: "N/A"

if (new File(outputFileName).parentFile)
    new File(outputFileName).parentFile.mkdirs()

def rawReadCounts = new HashMap<String, Integer>()
def totalRawReads = 0, totalConsensusReads = 0
int n = 0
println "[${new Date()} $scriptName] Reading raw clonotypes from $rawInputFileName.."
new File(rawInputFileName).splitEachLine("\t") { splitLine ->
    if (splitLine[0].isInteger()) {
        //if (!filterNonFunctional || splitLine[AA_SEQ_COL].matches("[a-zA-Z]+")) {
        def cdrKey = getCdrKey(splitLine)

        rawReadCounts.put(cdrKey, (rawReadCounts[splitLine[NT_SEQ_COL]] ?: 0) +
                Integer.parseInt(splitLine[READ_COUNT_COL]))
        //}
        totalRawReads += Integer.parseInt(splitLine[READ_TOTAL_COL])

        if (++n % 500000 == 0)
            println "[${new Date()} $scriptName] $n clonotypes read"
    }
}

// IMPORTANT:
// Set V and J segments for a given CDR3nt as the ones with top count, i.e. collapse by CDR3
def cdr2signature = new HashMap<String, String>()
def nonFunctionalCdrs = new HashSet<String>()
def cdr2count = new HashMap<String, int[]>()
println "[${new Date()} $scriptName] Reading assembled clonotypes from $asmInputFileName and filtering"
n = 0
new File(asmInputFileName).splitEachLine("\t") { splitLine ->
    if (splitLine[0].isInteger()) {
        String cdrKey = getCdrKey(splitLine)
        def signature = cdr2signature[cdrKey]
        int goodEvents = Integer.parseInt(splitLine[EVENT_COUNT_COL]), eventsTotal = Integer.parseInt(splitLine[EVENT_TOTAL_COL]),
            goodReads = Integer.parseInt(splitLine[READ_COUNT_COL]), readsTotal = Integer.parseInt(splitLine[READ_TOTAL_COL])

        //if ((!filterNonFunctional || splitLine[AA_SEQ_COL].matches(/[a-zA-Z]+/))
        //        && (includeNonCanonical || splitLine[AA_SEQ_COL].matches(/^C(.+)[FW]$/))) {
        if (includeNonCanonical || splitLine[AA_SEQ_COL].matches(/^C(.+)[FW]$/)) {
            if (!splitLine[AA_SEQ_COL].matches(/[a-zA-Z]+/))
                nonFunctionalCdrs.add(cdrKey)

            // first col in signature is counter
            if (signature == null || (collapse && Integer.parseInt(signature.split("\t")[0]) < goodReads))
                cdr2signature.put(cdrKey, [goodReads, splitLine[DATA_FROM..DATA_TO]].flatten().join("\t"))

            int[] counters = cdr2count[cdrKey]
            if (counters == null)
                cdr2count.put(cdrKey, counters = new int[4])
            counters[0] += goodEvents
            counters[1] += eventsTotal
            counters[2] += goodReads
            counters[3] += readsTotal
        }

        totalConsensusReads += readsTotal

        if (++n % 500000 == 0)
            println "[${new Date()} $scriptName] $n clonotypes read"
    }
}

println "[${new Date()} $scriptName] Filtering.."

// Filter: at least 1 good event & read accumulation > 100% (by default)
def passFilter = { String cdrKey, int[] counters, Integer rawReads ->
    boolean unitFilterPassed = true

    if (filterUnits && counters[0] == 1) {
        def splitSignature = cdrKey.split("\t")
        def cdrSeq = cdrKey[0], vdjString = collapse ? "" : splitSignature[1..3].join("\t")

        // A standard hash-based 1-loop single-mm search..
        final char[] charArray = cdrSeq.toCharArray()
        char oldChar, oldChar2, nt, nt2
        String otherSeq
        int[] otherCounters
        for (int i = 0; i < charArray.length; i++) {
            oldChar = charArray[i]
            for (int j = 0; j < 4; j++) {
                nt = Util.code2nt(j)
                if (nt != oldChar) {
                    charArray[i] = nt
                    otherSeq = collapse ? new String(charArray) :
                            new String(charArray) + "\t" + vdjString

                    otherCounters = cdr2count[otherSeq]

                    if (otherCounters &&
                            otherCounters[2] > unitFilterRatio1mm * counters[2]) {
                        unitFilterPassed = false
                        break
                    }

                    // Embedded 2nd mm search
                    for (int k = i + 1; k < charArray.length; k++) {
                        oldChar2 = charArray[k]
                        for (int l = 0; l < 4; l++) {
                            nt2 = Util.code2nt(l)
                            if (nt2 != oldChar2) {
                                otherSeq = collapse ? new String(charArray) :
                                        new String(charArray) + "\t" + vdjString

                                otherCounters = cdr2count[otherSeq]
                                if (otherCounters &&
                                        otherCounters[2] > unitFilterRatio2mm * counters[2]) {
                                    unitFilterPassed = false
                                    break
                                }
                            }
                        }
                        charArray[k] = oldChar2

                        if (!unitFilterPassed)
                            break
                    }
                }
                if (!unitFilterPassed)
                    break
            }
            charArray[i] = oldChar
        }
    }

    unitFilterPassed &&
            (rawReads == null || // also output all clonotypes not detected in raw data, e.g. not extracted due to errors
                    (rawReads != null &&
                            counters[2] > readAccumulationThreshold *
                            rawReads * totalConsensusReads / totalRawReads))
}

int readsTotal = 0, readsFiltered = 0, eventsTotal = 0, eventsFiltered = 0, clonotypesFiltered = 0, clonotypesTotal = 0

def outputFile = new File(outputFileName)

def filter = Collections.newSetFromMap(new ConcurrentHashMap())

def totalUmis = new AtomicInteger(),
    nonFunctionalClonotypes = new AtomicInteger(),
    nonFunctionalEvents = new AtomicInteger(),
    nonFunctionalReads = new AtomicInteger()

outputFile.withPrintWriter { pw ->
    pw.println("Count\tPercentage\t" +
            "CDR3 nucleotide sequence\tCDR3 amino acid sequence\t" +
            "V segments\tJ segments\tD segments\t" +
            "Last V nucleotide position\t" +
            "First D nucleotide position\tLast D nucleotide position\t" +
            "First J nucleotide position\t" +
            "Good events\tTotal events\tGood reads\tTotal reads")

    // 1st pass - compute total and filter
    GParsPool.withPool THREADS, {
        cdr2count.eachParallel {
            def cdrKey = it.key
            def counters = it.value, rawReads = rawReadCounts[it.key]
            if (passFilter(cdrKey, counters, rawReads)) {
                boolean nonFunctional = nonFunctionalCdrs.contains(cdrKey)

                if (nonFunctional) {
                    nonFunctionalClonotypes.incrementAndGet()
                    nonFunctionalEvents.addAndGet(counters[0])
                    nonFunctionalReads.addAndGet(counters[2])
                }

                if (!nonFunctional || !filterNonFunctional) {
                    filter.add(cdrKey)
                    totalUmis.addAndGet(counters[0])
                }
            }
        }
    }

    // 2nd pass - output and record statistics
    cdr2count.sort { -it.value[0] }.each {
        def cdrKey = it.key
        def signature = cdr2signature[cdrKey].split("\t")[1..-1].join("\t") // omit counter
        def counters = it.value
        if (filter.contains(cdrKey))
            pw.println(counters[0] + "\t" + (counters[0] / (double) totalUmis.get()) + "\t" + signature)
        else {
            readsFiltered += counters[2]
            eventsFiltered += counters[0]
            clonotypesFiltered++
        }
        readsTotal += counters[2]
        eventsTotal += counters[0]
        clonotypesTotal++
    }
}

println "[${new Date()} $scriptName] Finished, ${Util.getPercent(clonotypesFiltered, clonotypesTotal)} clonotypes, " +
        "${Util.getPercent(eventsFiltered, eventsTotal)} events and " +
        "${Util.getPercent(readsFiltered, readsTotal)} reads filtered"

// Append to log and report to batch runner
def logLine = [
        outputFile.absolutePath, rawInputFileName, asmInputFileName,
        clonotypesFiltered, clonotypesTotal,
        eventsFiltered, eventsTotal,
        readsFiltered, readsTotal,
        nonFunctionalClonotypes.get(), nonFunctionalEvents.get(), nonFunctionalReads.get()
].join("\t")

if (logFileName) {
    def logFile = new File(logFileName)

    if (logFile.exists()) {
        if (overwriteLog)
            logFile.delete()
    } else {
        logFile.absoluteFile.parentFile.mkdirs()
        logFile.withPrintWriter { pw ->
            pw.println(Util.CDRBLASTFILTER_LOG_HEADER)
        }
    }

    logFile.withWriterAppend { logWriter ->
        logWriter.println("$sampleName\t" + logLine)
    }
}

return logLine