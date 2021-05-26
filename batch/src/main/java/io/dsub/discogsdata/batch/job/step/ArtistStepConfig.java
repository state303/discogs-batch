package io.dsub.discogsdata.batch.job.step;

import io.dsub.discogsdata.batch.BatchCommand;
import io.dsub.discogsdata.batch.domain.artist.ArtistXML;
import io.dsub.discogsdata.batch.dump.service.DiscogsDumpService;
import io.dsub.discogsdata.batch.job.listener.StopWatchStepExecutionListener;
import io.dsub.discogsdata.batch.job.listener.StringFieldNormalizingItemReadListener;
import io.dsub.discogsdata.batch.job.processor.ArtistSubItemsProcessor;
import io.dsub.discogsdata.batch.job.reader.DumpItemReaderBuilder;
import io.dsub.discogsdata.batch.job.tasklet.FileFetchTasklet;
import io.dsub.discogsdata.common.entity.artist.Artist;
import io.dsub.discogsdata.common.exception.InitializationFailureException;
import java.util.Collection;
import java.util.concurrent.Future;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.JobScope;
import org.springframework.batch.core.configuration.annotation.StepBuilderFactory;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.FlowBuilder;
import org.springframework.batch.core.job.flow.Flow;
import org.springframework.batch.core.job.flow.FlowStep;
import org.springframework.batch.core.job.flow.support.SimpleFlow;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.integration.async.AsyncItemProcessor;
import org.springframework.batch.integration.async.AsyncItemWriter;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.support.SynchronizedItemStreamReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@RequiredArgsConstructor
public class ArtistStepConfig {

  private static final String CHUNK = "#{jobParameters['chunkSize']}";
  private static final String THROTTLE = "#{jobParameters['throttleLimit']}";
  private static final String ETAG = "#{jobParameters['artist']}";
  private static final String ARTIST_STEP_FLOW = "artist step flow";
  private static final String ARTIST_FLOW_STEP = "artist flow step";
  private static final String ARTIST_CORE_STEP = "artist core step";
  private static final String ARTIST_SUB_ITEMS_STEP = "artist sub items step";
  private static final String ARTIST_FILE_FETCH_STEP = "artist file fetch step";
  private static final String ANY = "*";
  private static final String FAILED = "FAILED";

  private final StepBuilderFactory sbf;
  private final DiscogsDumpService dumpService;
  private final ItemWriter<Artist> artistItemWriter;
  private final ThreadPoolTaskExecutor taskExecutor;
  private final ArtistSubItemsProcessor artistSubItemsProcessor;
  private final ItemWriter<Collection<BatchCommand>> artistSubItemWriter;
  private final JobRepository jobRepository;

  @Bean
  @JobScope
  // TODO: add file fetch and clear step
  public Step artistStep() throws Exception {
    Flow artistStepFlow =
        new FlowBuilder<SimpleFlow>(ARTIST_STEP_FLOW)
            .from(fileFetchStep(null)).on(FAILED).end()
            .from(fileFetchStep(null)).on(ANY).to(artistCoreStep(null, null))
            .from(artistCoreStep(null,null)).on(FAILED).end()
            .from(artistCoreStep(null,null)).on(ANY).to(artistSubItemsStep(null,null))
            .from(artistSubItemsStep(null,null)).on(ANY).end()
            .build();
    FlowStep artistFlowStep = new FlowStep();
    artistFlowStep.setJobRepository(jobRepository);
    artistFlowStep.setName(ARTIST_FLOW_STEP);
    artistFlowStep.setStartLimit(Integer.MAX_VALUE);
    artistFlowStep.setFlow(artistStepFlow);
    return artistFlowStep;
  }

  @Bean
  @JobScope
  public Step artistCoreStep(
      @Value(CHUNK) Integer chunkSize, @Value(THROTTLE) Integer throttleLimit) {
    SynchronizedItemStreamReader<ArtistXML> artistDTOItemReader = artistStreamReader(null);
    return sbf.get(ARTIST_CORE_STEP)
        .<ArtistXML, Artist>chunk(chunkSize)
        .reader(artistDTOItemReader)
        .processor(artistProcessor())
        .writer(artistItemWriter)
        .faultTolerant()
        .retryLimit(10)
        .retry(DeadlockLoserDataAccessException.class)
        .listener(new StringFieldNormalizingItemReadListener<>())
        .listener(new StopWatchStepExecutionListener())
        .taskExecutor(taskExecutor)
        .throttleLimit(throttleLimit)
        .build();
  }

  @Bean
  @JobScope
  public Step artistSubItemsStep(
      @Value(CHUNK) Integer chunkSize, @Value(THROTTLE) Integer throttleLimit) throws Exception {
    return sbf.get(ARTIST_SUB_ITEMS_STEP)
        .<ArtistXML, Future<Collection<BatchCommand>>>chunk(chunkSize)
        .reader(artistStreamReader(null))
        .processor(asyncArtistSubItemsProcessor())
        .writer(asyncArtistSubItemsWriter())
        .faultTolerant()
        .retryLimit(10)
        .retry(DeadlockLoserDataAccessException.class)
        .listener(new StringFieldNormalizingItemReadListener<>())
        .listener(new StopWatchStepExecutionListener())
        .throttleLimit(throttleLimit)
        .build();
  }

  @Bean
  @JobScope
  public Step fileFetchStep(@Value(ETAG) String artistETag) {
    return sbf.get(ARTIST_FILE_FETCH_STEP)
        .tasklet(new FileFetchTasklet(dumpService.getDiscogsDump(artistETag)))
        .build();
  }

  @Bean
  @StepScope
  public AsyncItemProcessor<ArtistXML, Collection<BatchCommand>> asyncArtistSubItemsProcessor()
      throws Exception {
    AsyncItemProcessor<ArtistXML, Collection<BatchCommand>> processor = new AsyncItemProcessor<>();
    processor.setDelegate(artistSubItemsProcessor);
    processor.setTaskExecutor(taskExecutor);
    processor.afterPropertiesSet();
    return processor;
  }

  @Bean
  @StepScope
  public AsyncItemProcessor<ArtistXML, Artist> asyncArtistProcessor() {
    AsyncItemProcessor<ArtistXML, Artist> processor = new AsyncItemProcessor<>();
    processor.setTaskExecutor(taskExecutor);
    processor.setDelegate(artistProcessor());
    return processor;
  }

  @Bean
  @StepScope
  public AsyncItemWriter<Artist> asyncArtistItemWriter() {
    AsyncItemWriter<Artist> writer = new AsyncItemWriter<>();
    writer.setDelegate(artistItemWriter);
    return writer;
  }

  @Bean
  @StepScope
  public AsyncItemWriter<Collection<BatchCommand>> asyncArtistSubItemsWriter() {
    AsyncItemWriter<Collection<BatchCommand>> writer = new AsyncItemWriter<>();
    writer.setDelegate(artistSubItemWriter);
    return writer;
  }

  @Bean
  @StepScope
  public SynchronizedItemStreamReader<ArtistXML> artistStreamReader(@Value(ETAG) String eTag) {
    try {
      return DumpItemReaderBuilder.build(ArtistXML.class, dumpService.getDiscogsDump(eTag));
    } catch (Exception e) {
      throw new InitializationFailureException(
          "failed to initialize artist stream reader: " + e.getMessage());
    }
  }

  @Bean
  @StepScope
  public ItemProcessor<ArtistXML, Artist> artistProcessor() {
    return xml -> Artist.builder()
        .id(xml.getId())
        .name(xml.getName())
        .realName(xml.getRealName())
        .dataQuality(xml.getDataQuality())
        .profile(xml.getProfile())
        .build();
  }
}