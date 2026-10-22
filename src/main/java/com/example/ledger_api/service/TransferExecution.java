package com.example.ledger_api.service;

import com.example.ledger_api.dto.transfer.TransferResponse;

public record TransferExecution(TransferResponse transfer, boolean replayed) {}