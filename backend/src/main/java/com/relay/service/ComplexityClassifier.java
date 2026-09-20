package com.relay.service;

import com.relay.model.ComplexityLevel;
import com.relay.model.FileCandidate;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ComplexityClassifier {

    public ComplexityLevel classify(List<FileCandidate> files) {
        int score = 0;
        score += files.size() * 5;
        for (FileCandidate fc : files) {
            score += fc.getDownstreamCount() * 3;
        }
        score += 6; // mock AST depth contribution

        if (score <= 20)
            return ComplexityLevel.SMALL;
        if (score <= 50)
            return ComplexityLevel.MEDIUM;
        if (score <= 75)
            return ComplexityLevel.COMPLEX;
        return ComplexityLevel.EPIC;
    }
}