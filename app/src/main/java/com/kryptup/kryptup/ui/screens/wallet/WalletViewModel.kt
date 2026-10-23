package com.kryptup.kryptup.ui.screens.wallet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kryptup.kryptup.data.model.Token
import com.kryptup.kryptup.data.model.TransactionRecord
import com.kryptup.kryptup.data.model.TransactionType
import com.kryptup.kryptup.data.model.WalletTransaction
import com.kryptup.kryptup.data.repository.EthereumRepository
import com.kryptup.kryptup.data.repository.MarketRepository
import com.kryptup.kryptup.data.repository.TransactionRepository
import com.kryptup.kryptup.data.wallet.WalletManager
import com.kryptup.kryptup.security.PinSecurityManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class WalletViewModel @Inject constructor(
    private val walletManager: WalletManager,
    private val ethereumRepository: EthereumRepository,
    private val marketRepository: MarketRepository,
    val pinSecurityManager: PinSecurityManager,
    private val transactionRepository: TransactionRepository
) : ViewModel() {

    // Ephemeral setup state — only holds generated words during the initial creation step.
    // Cleared from memory as soon as wallet creation is completed.
    private val _mnemonic = MutableStateFlow("")
    val mnemonicForSetup: StateFlow<String> = _mnemonic.asStateFlow()

    private var lastKnownEthPrice: BigDecimal? = null

    private val _address = MutableStateFlow("")
    val address: StateFlow<String> = _address

    private val _tokens = MutableStateFlow<List<Token>>(emptyList())
    val tokens: StateFlow<List<Token>> = _tokens

    private val _transactions = MutableStateFlow<List<WalletTransaction>>(emptyList())
    val transactions: StateFlow<List<WalletTransaction>> = _transactions

    private val _totalBalanceUsd = MutableStateFlow(BigDecimal.ZERO)
    val totalBalanceUsd: StateFlow<BigDecimal> = _totalBalanceUsd

    private val _isSending = MutableStateFlow(false)
    val isSending = _isSending.asStateFlow()

    private val _transactionStatus = MutableStateFlow<String?>(null)
    val transactionStatus = _transactionStatus.asStateFlow()

    init {
        val savedMnemonic = walletManager.getMnemonic()
        if (savedMnemonic != null) {
            // Derive address from secure storage directly — never expose seed phrase in UI state
            _address.value = walletManager.createWallet(savedMnemonic, "")
            refreshBalances()
        }
    }

    fun generateMnemonic() {
        _mnemonic.value = walletManager.generateMnemonic()
    }

    fun createWallet(pass: String = "") {
        val mnemonicStr = _mnemonic.value.ifEmpty { walletManager.getMnemonic() ?: "" }
        if (mnemonicStr.isNotEmpty()) {
            walletManager.saveMnemonic(mnemonicStr)
            _address.value = walletManager.createWallet(mnemonicStr, pass)
            // Immediately wipe mnemonic from ViewModel UI memory
            _mnemonic.value = ""
            refreshBalances()
        }
    }

    fun refreshBalances() {
        val currentAddress = _address.value
        if (currentAddress.isNotEmpty()) {
            viewModelScope.launch {
                val ethBalance = ethereumRepository.getEthBalance(currentAddress)
                val usdtBalance = ethereumRepository.getErc20Balance(
                    "0x7169D3881550a327F110196142ccA57422789644", // Sepolia USDT
                    currentAddress,
                    6
                )
                
                _tokens.value = listOf(
                    Token("ETH", "Ethereum", null, ethBalance),
                    Token("USDT", "Tether", "0x7169D3881550a327F110196142ccA57422789644", usdtBalance, 6)
                )

                _transactions.value = ethereumRepository.getTransactionHistory(currentAddress)

                // Fetch live ETH price from CoinGecko
                val priceMap = marketRepository.getPrices("ethereum,tether")
                val ethPriceDouble = priceMap["ethereum"]?.get("usd")
                if (ethPriceDouble != null && ethPriceDouble > 0) {
                    lastKnownEthPrice = BigDecimal(ethPriceDouble.toString())
                }

                val ethPrice = lastKnownEthPrice ?: BigDecimal.ZERO
                val usdtPrice = BigDecimal("1")
                if (ethPrice > BigDecimal.ZERO) {
                    _totalBalanceUsd.value = ethBalance.multiply(ethPrice).add(usdtBalance.multiply(usdtPrice))
                } else {
                    _totalBalanceUsd.value = usdtBalance.multiply(usdtPrice)
                }
            }
        }
    }

    fun reset() {
        walletManager.clearWallet()
        _mnemonic.value = ""
        _address.value = ""
        _tokens.value = emptyList()
    }

    fun sendTransaction(toAddress: String, amountStr: String, symbol: String) {
        val cleanAddress = toAddress.trim()
        if (!cleanAddress.matches(Regex("^0x[0-9a-fA-F]{40}$"))) {
            _transactionStatus.value = "Error: Invalid recipient Ethereum address (must be 0x followed by 40 hex characters)."
            return
        }

        val amount = amountStr.toBigDecimalOrNull()
        if (amount == null || amount <= BigDecimal.ZERO) {
            _transactionStatus.value = "Error: Please enter a valid positive amount."
            return
        }

        val availableBalance = _tokens.value.find { it.symbol.equals(symbol, ignoreCase = true) }?.balance ?: BigDecimal.ZERO
        if (amount > availableBalance) {
            _transactionStatus.value = "Error: Insufficient balance. Available: $availableBalance $symbol"
            return
        }

        val signingMnemonic = walletManager.getMnemonic() ?: _mnemonic.value
        if (signingMnemonic.isEmpty()) {
            _transactionStatus.value = "Error: No wallet active. Please create or import a wallet first."
            return
        }

        viewModelScope.launch {
            _isSending.value = true
            _transactionStatus.value = "Processing..."
            try {
                val credentials = walletManager.getCredentialsFromMnemonic(signingMnemonic, "")
                val hash = if (symbol == "ETH") {
                    ethereumRepository.sendEth(credentials, cleanAddress, amount)
                } else {
                    val token = _tokens.value.find { it.symbol == symbol }
                    ethereumRepository.sendErc20(
                        credentials,
                        token?.address ?: "",
                        cleanAddress,
                        amount,
                        token?.decimals ?: 18
                    )
                }
                _transactionStatus.value = "Success! Hash: $hash"

                // Log in TransactionRepository
                transactionRepository.recordTransaction(
                    TransactionRecord(
                        type = TransactionType.CRYPTO_SEND,
                        title = "Sent $amountStr $symbol",
                        description = "Transfer to ${toAddress.take(8)}...",
                        amountDisplay = "-$amountStr $symbol",
                        amountValue = amount.toDouble(),
                        isDebit = true,
                        category = "Transfer",
                        referenceId = hash.take(12)
                    )
                )

                refreshBalances()
            } catch (e: Exception) {
                _transactionStatus.value = "Error: ${e.message}"
            } finally {
                _isSending.value = false
            }
        }
    }
}
