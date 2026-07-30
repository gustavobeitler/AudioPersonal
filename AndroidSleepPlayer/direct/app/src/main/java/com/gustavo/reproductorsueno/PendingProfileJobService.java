package com.gustavo.reproductorsueno;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

public class PendingProfileJobService extends JobService {
    private static final int JOB_ID = 4105;
    private volatile boolean cancelled;

    public static void schedule(Context context, String brand, String model) {
        SharedPreferences prefs = context.getSharedPreferences("reproductor_sueno", MODE_PRIVATE);
        prefs.edit()
                .putString("pending_profile_brand", brand)
                .putString("pending_profile_model", model)
                .putString("pending_profile_result", "pending")
                .apply();

        JobScheduler scheduler = (JobScheduler) context.getSystemService(JOB_SCHEDULER_SERVICE);
        if (scheduler == null) return;
        JobInfo info = new JobInfo.Builder(JOB_ID,
                new ComponentName(context, PendingProfileJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                .setBackoffCriteria(30_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .build();
        scheduler.schedule(info);
    }

    @Override public boolean onStartJob(JobParameters params) {
        cancelled = false;
        new Thread(() -> runSearch(params), "pending-profile-wifi").start();
        return true;
    }

    private void runSearch(JobParameters params) {
        SharedPreferences prefs = getSharedPreferences("reproductor_sueno", MODE_PRIVATE);
        String brand = prefs.getString("pending_profile_brand", "");
        String model = prefs.getString("pending_profile_model", "");
        if (brand.isEmpty() || model.isEmpty() || cancelled) {
            jobFinished(params, false);
            return;
        }

        try {
            HeadphoneProfileRepository.Result result =
                    HeadphoneProfileRepository.searchSync(this, brand, model);
            if (cancelled) {
                jobFinished(params, true);
                return;
            }
            if (result == null) {
                prefs.edit()
                        .remove("pending_profile_brand")
                        .remove("pending_profile_model")
                        .putString("pending_profile_result", "not_found")
                        .putString("pending_profile_display", brand + " " + model)
                        .apply();
                jobFinished(params, false);
                return;
            }

            String id = saveProfile(prefs, brand, model, result);
            prefs.edit()
                    .remove("pending_profile_brand")
                    .remove("pending_profile_model")
                    .putString("pending_profile_result", "found")
                    .putString("pending_profile_display", brand + " " + model)
                    .putString("active_profile", id)
                    .apply();
            jobFinished(params, false);
        } catch (Exception error) {
            if (!cancelled) jobFinished(params, true);
        }
    }

    private String saveProfile(SharedPreferences prefs, String brand, String model,
                               HeadphoneProfileRepository.Result result) throws Exception {
        JSONArray array = new JSONArray(prefs.getString("sound_profiles", "[]"));
        String requestedName = (brand + " " + model).trim();
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.getJSONObject(i);
            if (requestedName.equalsIgnoreCase(item.optString("name"))) return item.getString("id");
        }

        String id = "hp_" + System.currentTimeMillis();
        JSONObject item = new JSONObject();
        item.put("id", id);
        item.put("name", requestedName);
        item.put("description", "Perfil medido: " + result.source);
        item.put("preamp", result.preampDb);
        JSONArray gains = new JSONArray();
        for (float gain : result.gainsDb) gains.put(gain);
        item.put("gains", gains);
        array.put(item);
        prefs.edit().putString("sound_profiles", array.toString()).apply();
        return id;
    }

    @Override public boolean onStopJob(JobParameters params) {
        cancelled = true;
        return true;
    }
}