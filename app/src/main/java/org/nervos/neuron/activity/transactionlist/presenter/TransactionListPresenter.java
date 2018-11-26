package org.nervos.neuron.activity.transactionlist.presenter;

import android.app.Activity;
import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;
import android.widget.ImageView;
import android.widget.Toast;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestOptions;

import org.nervos.neuron.R;
import org.nervos.neuron.activity.transactionlist.model.TokenDescribeModel;
import org.nervos.neuron.item.EthErc20TokenInfoItem;
import org.nervos.neuron.item.TokenItem;
import org.nervos.neuron.item.WalletItem;
import org.nervos.neuron.item.transaction.TransactionItem;
import org.nervos.neuron.item.transaction.TransactionResponse;
import org.nervos.neuron.service.http.HttpService;
import org.nervos.neuron.util.db.DBAppChainTransactionsUtil;
import org.nervos.neuron.util.db.DBWalletUtil;
import org.nervos.neuron.util.ether.EtherUtil;
import org.nervos.neuron.util.url.HttpUrls;
import org.nervos.neuron.service.http.TokenService;
import org.nervos.neuron.util.AddressUtil;
import org.nervos.neuron.util.CurrencyUtil;
import org.nervos.neuron.util.db.DBEtherTransactionUtil;
import org.web3j.crypto.Keys;
import org.web3j.utils.Numeric;

import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import rx.Observable;
import rx.Subscriber;

/**
 * Created by BaojunCZ on 2018/10/9.
 */
public class TransactionListPresenter {

    private Activity activity;
    private TransactionListPresenterImpl listener;
    private TokenDescribeModel tokenDescribeModel;
    private TokenItem tokenItem;

    public TransactionListPresenter(Activity activity, TokenItem tokenItem, TransactionListPresenterImpl listener) {
        this.activity = activity;
        this.listener = listener;
        this.tokenItem = tokenItem;
    }

    public void setTokenLogo(ImageView tokenLogoImage) {
        if (EtherUtil.isEther(tokenItem)) {
            if (!isNativeToken(tokenItem)) {
                if (TextUtils.isEmpty(tokenItem.avatar)) {
                    String address = tokenItem.contractAddress;
                    if (AddressUtil.isAddressValid(address)) address = Keys.toChecksumAddress(address);
                    RequestOptions options = new RequestOptions().error(R.drawable.ether_big);
                    Glide.with(activity)
                            .load(Uri.parse(String.format(HttpUrls.TOKEN_LOGO, address)))
                            .apply(options).into(tokenLogoImage);
                } else {
                    Glide.with(activity)
                            .load(Uri.parse(tokenItem.avatar))
                            .into(tokenLogoImage);
                }
            }
        }
    }

    public void getTokenDescribe() {
        tokenDescribeModel = new TokenDescribeModel(new TokenDescribeModel.TokenDescribeModelImpl() {
            @Override
            public void success(EthErc20TokenInfoItem item) {
                listener.getTokenDescribe(item);
                listener.showTokenDescribe(true);
            }

            @Override
            public void error() {
            }
        });
        tokenDescribeModel.get(tokenItem.contractAddress);
    }

    public void getBalance() {
        if (tokenItem.balance > 0.0 && EtherUtil.isEther(tokenItem)) {
            TokenService.getCurrency(tokenItem.symbol, CurrencyUtil.getCurrencyItem(activity).getName())
                    .subscribe(new Subscriber<String>() {
                        @Override
                        public void onCompleted() {
                        }

                        @Override
                        public void onError(Throwable e) {
                            e.printStackTrace();
                        }

                        @Override
                        public void onNext(String s) {
                            if (!TextUtils.isEmpty(s)) {
                                double price = Double.parseDouble(s.trim());
                                DecimalFormat df = new DecimalFormat("######0.00");
                                DecimalFormat format = new DecimalFormat("0.####");
                                format.setRoundingMode(RoundingMode.FLOOR);
                                listener.getCurrency(CurrencyUtil.getCurrencyItem(activity).getSymbol()
                                        + Double.parseDouble(df.format(price * tokenItem.balance)));
                            } else {
                                listener.getCurrency("0");
                            }
                        }
                    });
        }
    }

    public void getTransactionList(int page) {
        Observable<List<TransactionResponse>> observable;
        if (isNativeToken(tokenItem)) {
            if (Numeric.toBigInt(tokenItem.getChainId()).longValue() > 1) {       // Now only support chainId = 1 (225 NATT), not support other chainId (> 1)
                getUnofficialNoneData();
                return;
            }
            observable = EtherUtil.isEther(tokenItem)
                    ? HttpService.getEtherTransactionList(activity, page)
                    : HttpService.getAppChainTransactionList(activity, page);
        } else {
            observable = EtherUtil.isEther(tokenItem)
                    ? HttpService.getEtherERC20TransactionList(activity, tokenItem, page)
                    : HttpService.getAppChainERC20TransactionList(activity, tokenItem, page);
        }
        observable.subscribe(new Subscriber<List<TransactionResponse>>() {
            @Override
            public void onCompleted() {
                listener.hideProgressBar();
                listener.setRefreshing(false);
            }

            @Override
            public void onError(Throwable e) {
                e.printStackTrace();
                Toast.makeText(activity, R.string.network_error, Toast.LENGTH_SHORT).show();
                listener.hideProgressBar();
                listener.setRefreshing(false);
            }

            @Override
            public void onNext(List<TransactionResponse> list) {
                if (list.size() == 0) {
                    listener.noMoreLoading();
                    return;
                }
                if (EtherUtil.isEther(tokenItem)) {
                    list = removeRepetition(list);
                    for (TransactionResponse item : list) {
                        item.chainId = EtherUtil.getEtherId();
                        item.chainName = EtherUtil.getEthNodeName();
                        item.status = TextUtils.isEmpty(item.errorMessage) ? TransactionItem.SUCCESS : TransactionItem.FAILED;
                    }
                    list = getEtherTransactionList(activity, EtherUtil.getEtherId(), list);
                    if (page == 0) {
                        listener.updateNewList(list);
                    } else {
                        listener.refreshList(list);
                    }
                } else {
                    for (TransactionResponse item : list) {
                        item.status = TextUtils.isEmpty(item.errorMessage) ? TransactionItem.SUCCESS : TransactionItem.FAILED;
                    }
                    list = getAppChainTransactionList(activity, tokenItem.getChainId(), list);
                    if (page == 0) {
                        listener.updateNewList(list);
                    } else {
                        listener.refreshList(list);
                    }
                }
            }
        });
    }


    private List<TransactionResponse> getEtherTransactionList(Context context, String chainId, List<TransactionResponse> list) {
        WalletItem walletItem = DBWalletUtil.getCurrentWallet(context);
        List<TransactionItem> itemList = DBEtherTransactionUtil.getAllTransactionsWithToken(context, chainId, tokenItem.contractAddress);
        if (itemList.size() > 0) {
            Iterator<TransactionItem> iterator = itemList.iterator();
            while (iterator.hasNext()) {
                TransactionItem dbItem = iterator.next();
                for (TransactionResponse item : list) {
                    if (!walletItem.address.equals(dbItem.from) && !walletItem.address.equals(dbItem.to)) {
                        iterator.remove();
                        break;
                    }
                    if (item.hash.equalsIgnoreCase(dbItem.hash) || dbItem.getTimestamp() < list.get(list.size() - 1).getTimestamp()) {
                        iterator.remove();
                        break;
                    }
                }
            }
            for (TransactionItem item : itemList) {
                list.add(new TransactionResponse(item));
            }
            Collections.sort(list, (o1, o2) -> o2.getDate().compareTo(o1.getDate()));
        }
        return list;
    }

    private List<TransactionResponse> getAppChainTransactionList(Context context, String chainId, List<TransactionResponse> list) {
        WalletItem walletItem = DBWalletUtil.getCurrentWallet(context);
        List<TransactionItem> itemList = DBAppChainTransactionsUtil.getAllTransactionsWithToken(context, chainId, tokenItem.contractAddress);
        if (itemList.size() > 0) {
            Iterator<TransactionItem> iterator = itemList.iterator();
            while (iterator.hasNext()) {
                TransactionItem dbItem = iterator.next();
                for (TransactionResponse item : list) {
                    if (!walletItem.address.equals(dbItem.from) && !walletItem.address.equals(dbItem.to)) {
                        iterator.remove();
                        break;
                    }
                    if (item.hash.equalsIgnoreCase(dbItem.hash) || dbItem.getTimestamp() < list.get(list.size() - 1).getTimestamp()) {
                        iterator.remove();
                        break;
                    }
                }
            }
            for (TransactionItem item : itemList) {
                list.add(new TransactionResponse(item));
            }
            Collections.sort(list, (o1, o2) -> o2.getDate().compareTo(o1.getDate()));
        }
        return list;
    }


    public static List<TransactionResponse> removeRepetition(List<TransactionResponse> list) {
        List<TransactionResponse> tempList = new ArrayList<>();
        for (TransactionResponse item : list) {
            if (!tempList.contains(item)) {
                tempList.add(item);
            }
        }
        return tempList;
    }


    private void getUnofficialNoneData() {
        listener.hideProgressBar();
        listener.setRefreshing(false);
    }

    public boolean isNativeToken(TokenItem tokenItem) {
        return TextUtils.isEmpty(tokenItem.contractAddress);
    }

    public interface TransactionListPresenterImpl {
        void hideProgressBar();

        void setRefreshing(boolean refreshing);

        void updateNewList(List<TransactionResponse> list);

        void refreshList(List<TransactionResponse> list);

        void getTokenDescribe(EthErc20TokenInfoItem item);

        void showTokenDescribe(boolean show);

        void getCurrency(String currency);

        void noMoreLoading();

    }

}
