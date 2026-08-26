package com.company.office.application;

import com.company.office.model.JobEntity;
import com.company.office.repository.JobRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class JobService {
    private final JobRepository jobRepository;

    public JobService(JobRepository jobRepository) {
        this.jobRepository = jobRepository;
    }

    public JobEntity createJob(String documentId, String jobType) {
        String id = "job-" + UUID.randomUUID().toString().substring(0, 8);
        JobEntity job = new JobEntity(id, documentId, jobType);
        jobRepository.save(job);
        return job;
    }

    public void updateProgress(String jobId, String status, double progress, String resultJson, String errorMessage) {
        Optional<JobEntity> opt = jobRepository.findById(jobId);
        if (opt.isPresent()) {
            JobEntity job = opt.get();
            job.setStatus(status);
            job.setProgress(progress);
            if (resultJson != null) job.setResultData(resultJson);
            if (errorMessage != null) job.setErrorMessage(errorMessage);
            if ("COMPLETED".equals(status) || "FAILED".equals(status)) {
                job.setCompletedAt(LocalDateTime.now());
            }
            jobRepository.save(job);
        }
    }

    public Optional<JobEntity> getJob(String jobId) {
        return jobRepository.findById(jobId);
    }

    public List<JobEntity> getAllJobs() {
        return jobRepository.findAll();
    }
}
