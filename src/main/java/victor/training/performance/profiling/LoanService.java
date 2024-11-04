package victor.training.performance.profiling;

import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import victor.training.performance.profiling.dto.CommentDto;
import victor.training.performance.profiling.dto.LoanApplicationDto;
import victor.training.performance.profiling.entity.Audit;
import victor.training.performance.profiling.entity.LoanApplication;
import victor.training.performance.profiling.entity.LoanApplication.Status;
import victor.training.performance.profiling.repo.AuditRepo;
import victor.training.performance.profiling.repo.LoanApplicationRepo;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
@RequiredArgsConstructor
//@Transactional // or @TransactionalAttribute (JEE) Transaction on class level is not recommended because it can lead to deadlocks and performance issues
public class LoanService {
  private final LoanApplicationRepo loanApplicationRepo;
  private final CommentsApiClient commentsApiClient;


    //1) Avoid doing API calls (REST/SOAP/COBOL) while holding a DB transaction/Connection
  //because connection are a scare  precious resource and should be released as soon as possible
  @SneakyThrows
//  @Transactional // I don't really need  Tx here since I'm just SELECTing data
  // The Transactional annotation is used to indicate that a method is a transactional method but if use only to get data from the database, it is not necessary to use it
  //because the method is read-only and the transaction is not necessary, and it aquires a connection to the database and it is not necessary
  // getConnection from the database pool
//  the issue here is that the connections were held for a long time and the pool was exhausted
//  issue=connection pool starvation issue; because of unfair usage. the code make Bad use of connection.
//  fix: release the connection faster back to the pool
  public LoanApplicationDto getLoanApplication(Long loanId) {
    log.info("Start");
    List<CommentDto> comments = commentsApiClient.fetchComments(loanId); // takes ±40ms in prod
    LoanApplication loanApplication = loanApplicationRepo.findByIdLoadingSteps(loanId);
    LoanApplicationDto dto = new LoanApplicationDto(loanApplication, comments);
    log.trace("Loan app: " + loanApplication);
    return dto;
  }

  private final AuditRepo auditRepo;
  @Transactional // needed here💖
  public void saveLoanApplication(String title) {
    Long id = loanApplicationRepo.save(new LoanApplication().setTitle(title)).getId();
    auditRepo.save(new Audit("Loan created: " + id));
  }

  private final List<Long> recentLoanStatusQueried = new ArrayList<>();

  @Transactional
  public synchronized Status getLoanStatus(Long loanId) {
    LoanApplication loanApplication = loanApplicationRepo.findById(loanId).orElseThrow();
    recentLoanStatusQueried.remove(loanId); // BUG#7235 - avoid duplicates in list
    recentLoanStatusQueried.add(loanId);
    while (recentLoanStatusQueried.size() > 10) recentLoanStatusQueried.remove(0);
    return loanApplication.getCurrentStatus();
  }

  private final ThreadPoolTaskExecutor executor;

  @Transactional
  public List<Long> getRecentLoanStatusQueried() {
    log.info("In parent thread");
    CompletableFuture.runAsync(() -> log.info("In a child thread"), executor).join();
    return new ArrayList<>(recentLoanStatusQueried);
  }

}
