import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import harreader.HarReader;
import harreader.HarReaderException;
import harreader.model.Har;
import harreader.model.HarEntry;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.PrintWriter;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;


public class HarFileModel {
    private CsvWriter csvWriter = new CsvWriter();
    private JsonWriter jsonWriter = new JsonWriter();
    // with LinkedHashMap we preserve insertion order in Hashmap
    private LinkedHashMap<String, ArrayList<ResourceInfo>> timeHarMap = new LinkedHashMap<String, ArrayList<ResourceInfo>>();
    private int fileCount;
    private String fileName;
    //private LinkedList<ProcessCombinationModel> combinationStatistics = new LinkedList<>();
    //
    public HarFileModel() throws HarReaderException, FileNotFoundException {
        int nCombInit = Integer.parseInt(System.getProperty("NUMBER_COMBINATIONS_INIT"));
        int nCombFinal = Integer.parseInt(System.getProperty("NUMBER_COMBINATIONS_FINAL"));
        System.out.println("Combinations from " +nCombInit + " to " + nCombFinal);
        this.fileCount = FillResourcesMap(System.getProperty("INPUT_PATH"), System.getProperty("FILE_NAME"), timeHarMap);

        for (int i=nCombInit;i<nCombFinal; i++) {
            File fileOutput = new File(System.getProperty("OUTPUT_PATH") + "results_" + i + "combin.csv");
            this.combinations(i, this.fileCount, fileOutput);
            if(fileOutput.length() < 50) break;
        }

        //csvWriter.SaveResourcesCombinationsProbabilities(this.combinationStatistics, "results.csv");
    }

    private static Float percentile(List<Float> latencies, double percentile) {
        Collections.sort(latencies);
        int index = (int) Math.ceil(percentile / 100.0 * latencies.size());
        return latencies.get(index-1);
    }

    /**
     * Load Files into Memory
     * @param path files folder
     * @param fileName
     * @param timeHarMap output memory structure
     * @throws HarReaderException
     */
    public int FillResourcesMap(String path, String fileName, LinkedHashMap<String, ArrayList<ResourceInfo>> timeHarMap) throws HarReaderException {
        int[] count = new int[]{0};
        int fileCount = 0;
        HarReader harReader = new HarReader();
        File file = new File(path + fileName + ".har");
        System.out.println(path + fileName + ".har");
        while (file.exists()){
            Har otherHar = harReader.readFromFile(file);
            for (HarEntry otherEntry : otherHar.getLog().getEntries()) {
                if (!otherEntry.getResponse().getHeaders().get(0).getValue().contains("no-cache")) {
                    ResourceInfo resourceInfo = new ResourceInfo();
                    resourceInfo.resourceTime = (float) otherEntry.getTime();
                    resourceInfo.resourceType = otherEntry.get_resourceType();
                    resourceInfo.cachedResource = otherEntry.getResponse().getHeaders().get(0).getValue();
                    resourceInfo.resourceLength = otherEntry.getResponse().getBodySize();
                    resourceInfo.harRun = fileCount;

                    if (timeHarMap.containsKey(otherEntry.getRequest().getUrl())) {
                        ArrayList<ResourceInfo> list = timeHarMap.get(otherEntry.getRequest().getUrl());
                        AtomicBoolean repeatedCall = new AtomicBoolean(false);
                        list.forEach(value -> {
                            if (value.resourceTime.equals(resourceInfo.resourceTime)) {
                                repeatedCall.set(true);
                                return;
                            }
                        });
                        if (!repeatedCall.get())
                            timeHarMap.get(otherEntry.getRequest().getUrl()).add(resourceInfo);
                    } else {
                        ArrayList<ResourceInfo> l = new ArrayList<>();
                        l.add(resourceInfo);
                        timeHarMap.put(otherEntry.getRequest().getUrl(), l);
                    }

                }
            }
            file = new File(path + fileName + "_" + ++fileCount +".har");
        }
        return fileCount;
    }

    /**
     * Compute statistics for each combination
     * @param len number of combinations
     * @param fileCount number of files (i.e., runs)
     * @param fileOutput
     */
    private void combinations(int len, int fileCount, File fileOutput) throws FileNotFoundException {
        ArrayList<String> resources= new ArrayList<>(timeHarMap.keySet());
        System.out.println("Número resources " + resources.size());
        Set<Set<String>> combinations = Sets.combinations(ImmutableSet.copyOf(resources), len);
        Set r;
        StringBuffer line = new StringBuffer();
        Iterator combIterator = combinations.iterator();
        System.out.println("Número combinações " + combinations.size());
        int i = 0;

        PrintWriter writer = new PrintWriter(fileOutput);
        StringBuilder sb = new StringBuilder();
        sb.append("Combination");
        sb.append(';');
        sb.append("Probability");
        sb.append(';');
        sb.append("CombinationLength");
        sb.append('\n');
        writer.write(sb.toString());

        while (combIterator.hasNext()){
            r = (Set) combIterator.next();

            line.setLength(0);
            Iterator lineIterator = r.iterator();
            while(lineIterator.hasNext()){
                if(line.length()>0) line.append(",");
                line.append(lineIterator.next().toString());
            }

            ProcessCombinationModel combinationInfo= new ProcessCombinationModel();
            combinationInfo.combination = line.toString();
            calculateStatistics(combinationInfo,fileCount, writer);
            if(i++%100000==0) System.out.println("Comb " + i);
        }
        writer.close();
    }

    /**
     * Auxiliary method that calculates statistics for each resource
     * @param combinationInfo single combination of resources
     * @param fileCount number of runs
     * @param writer
     */
    private void calculateStatistics(ProcessCombinationModel combinationInfo, int fileCount, PrintWriter writer){
        boolean resourceFound=false;

        String[] resources = combinationInfo.combination.split(","); // resources of each combination
        for (int i =0; i < fileCount; i++) { //controlo por run
            for(String combinationResource : resources){
                resourceFound=false;
                for(ResourceInfo comb : timeHarMap.get(combinationResource))
                    if(comb.harRun == i) {
                        resourceFound = true;
                        combinationInfo.resourceLength += comb.resourceLength;
                        break;
                    }
                if(! resourceFound){ break;}
            }
            if(resourceFound){
                combinationInfo.numberOfRuns++;
            }
        }

        combinationInfo.percentage = (float) combinationInfo.numberOfRuns/fileCount;

        if(combinationInfo.percentage > 0.5) {
        //    System.out.print("Combination, length, probability");
        //    for(String s: resources) System.out.print(System.identityHashCode(s) + "+");
        //    System.out.print(" " + combinationInfo.resourceLength + " " + combinationInfo.percentage + "\n");
            //this.combinationStatistics.add(combinationInfo);
            StringBuilder sb = new StringBuilder();
            sb.append(combinationInfo.combination + ";");
            sb.append((double) Math.round(combinationInfo.percentage*100)/100 + ";");
            sb.append(combinationInfo.resourceLength);
            sb.append('\n');
            writer.write(sb.toString());
        }
    }
}
