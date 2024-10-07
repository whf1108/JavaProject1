package com.xuecheng.media.service.jobhandler;

import com.xuecheng.base.utils.Mp4VideoUtil;
import com.xuecheng.media.model.po.MediaProcess;
import com.xuecheng.media.service.MediaFileProcessService;
import com.xuecheng.media.service.MediaFileService;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.*;

/**
 * 任务处理类
 */
@Slf4j
@Component
public class VideoTask {

    @Autowired
    MediaFileProcessService mediaFileProcessService;

    @Autowired
    MediaFileService mediaFileService;

    //ffmpeg的路径
    @Value("${videoprocess.ffmpegpath}")
    private String ffmpegpath;

    /**
     * 视频处理任务
     */
    @XxlJob("videoJobHandler")
    public void videoJobHandler() throws Exception {

        // 分片参数
        int shardIndex = XxlJobHelper.getShardIndex();//执行器的序号，从0开始
        int shardTotal = XxlJobHelper.getShardTotal();//执行器总数

        //确定cpu的核心数
        int processors = Runtime.getRuntime().availableProcessors();
        //查询待处理的任务
        List<MediaProcess> mediaProcessList = mediaFileProcessService.getMediaProcessList(shardIndex, shardTotal, processors);

        //任务数量
        int size = mediaProcessList.size();
        log.debug("取到视频处理任务数:"+size);
        if(size<=0){
            return;
        }
        //创建一个线程池
        ExecutorService executorService = Executors.newFixedThreadPool(size);
        //使用的计数器
        CountDownLatch countDownLatch = new CountDownLatch(size);
        mediaProcessList.forEach(mediaProcess -> {
            //将任务加入线程池
            executorService.execute(()->{
                try {
                    //任务id
                    Long taskId = mediaProcess.getId();
                    //文件id就是md5
                    String fileId = mediaProcess.getFileId();
                    //开启任务
                    boolean b = mediaFileProcessService.startTask(taskId);
                    if (!b) {  // 这里抢占是线程之间的抢占，不是执行器之间抢占？
                        log.debug("抢占任务失败,任务id:{}", taskId);
                        return;
                    }

                    //桶
                    String bucket = mediaProcess.getBucket();
                    //objectName
                    String objectName = mediaProcess.getFilePath();

                    //下载minio视频到本地
                    File file = mediaFileService.downloadFileFromMinIO(bucket, objectName);
                    if (file == null) {
                        log.debug("下载视频出错,任务id:{},bucket:{},objectName:{}", taskId, bucket, objectName);
                        //保存任务处理失败的结果
                        mediaFileProcessService.saveProcessFinishStatus(taskId, "3", fileId, null, "下载视频到本地失败");
                        return;
                    }

                    //源avi视频的路径
                    String video_path = file.getAbsolutePath();
                    //转换后mp4文件的名称
                    String mp4_name = fileId + ".mp4";
                    //转换后mp4文件的路径
                    //先创建一个临时文件，作为转换后的文件
                    File mp4File = null;
                    try {
                        mp4File = File.createTempFile("minio", ".mp4");
                    } catch (IOException e) {
                        log.debug("创建临时文件异常,{}", e.getMessage());
                        //保存任务处理失败的结果
                        mediaFileProcessService.saveProcessFinishStatus(taskId, "3", fileId, null, "创建临时文件异常");
                        return;
                    }
                    String mp4_path = mp4File.getAbsolutePath();
                    //创建工具类对象
                    Mp4VideoUtil videoUtil = new Mp4VideoUtil(ffmpegpath, video_path, mp4_name, mp4_path);
                    //开始视频转换，成功将返回success,失败返回失败原因
                    String result = videoUtil.generateMp4();
                    if (!result.equals("success")) {

                        log.debug("视频转码失败,原因:{},bucket:{},objectName:{},", result, bucket, objectName);
                        mediaFileProcessService.saveProcessFinishStatus(taskId, "3", fileId, null, result);
                        return;

                    }
                    //上传到minio
                    String objectName_mp4=objectName.substring(0,objectName.lastIndexOf("."))+".mp4";
                    boolean b1 = mediaFileService.addMediaFilesToMinIO(mp4File.getAbsolutePath(), "video/mp4", bucket, objectName_mp4);
                    if (!b1) {
                        log.debug("上传mp4到minio失败,taskid:{}", taskId);
                        mediaFileProcessService.saveProcessFinishStatus(taskId, "3", fileId, null, "上传mp4到minio失败");
                        return;
                    }
                    //mp4文件的url
                    // String url = getFilePath(fileId, ".mp4");  老师搞错了
                    String url=mediaProcess.getBucket()+objectName_mp4;
                    //更新任务状态为成功
                    mediaFileProcessService.saveProcessFinishStatus(taskId, "2", fileId, url, "创建临时文件异常");
                }finally {
                    //计算器减去1
                    countDownLatch.countDown();
                }

            });

        });

        //阻塞,指定最大限制的等待时间，阻塞最多等待一定的时间后就解除阻塞
        countDownLatch.await(30, TimeUnit.MINUTES);

    }

    private String getFilePath(String fileMd5,String fileExt){
        return   fileMd5.substring(0,1) + "/" + fileMd5.substring(1,2) + "/" + fileMd5 + "/" +fileMd5 +fileExt;
    }

    @XxlJob("myjob")
    public void my1()
    {
        int shardIndex = XxlJobHelper.getShardIndex();
        int shardTotal = XxlJobHelper.getShardTotal();

        // 1 领取任务
        int processors = Runtime.getRuntime().availableProcessors();
        List<MediaProcess> mediaProcessList = mediaFileProcessService.getMediaProcessList(shardIndex, shardTotal, processors);
        int size=mediaProcessList.size();
        if (size<=0)
        {
            return;
        }

        // CountDownLatch countDownLatch = new CountDownLatch(size);

        // 2 开启线程池处理任务
        ExecutorService threadPool = Executors.newFixedThreadPool(size);
        for (MediaProcess mediaProcess : mediaProcessList) {
            threadPool.execute(()->{
                Long id = mediaProcess.getId();
                boolean b = mediaFileProcessService.startTask(id);
                if(!b)  //
                {
                    log.info("抢占任务失败");
                    return;
                }
                // minio下载av4
                String bucket = mediaProcess.getBucket();
                String filePath = mediaProcess.getFilePath();
                String objectName=mediaProcess.getFilePath();
                String fileId = mediaProcess.getFileId();
                File file_av4 = mediaFileService.downloadFileFromMinIO(bucket, objectName); // 之前方法里下载av4是比较md5值
                if(file_av4==null)
                {
                    log.error("从minio下载视频失败");
                    // 别忘了这里需要更新数据库
                    mediaFileProcessService.saveProcessFinishStatus(mediaProcess.getId(),"3",fileId,null,"从minio下载视频失败");
                    return;
                }

                // 创建临时文件mp4
                File file_mp4 = null;
                try {
                    file_mp4 = File.createTempFile("minio", ".mp4");
                } catch (IOException e) {
                    log.error("创建临时文件异常");
                    mediaFileProcessService.saveProcessFinishStatus(mediaProcess.getId(),"3",fileId,null,"创建临时文件异常");
                    return;
                }

                String mp4_file_name=fileId+".mp4";  // 这个文件名称要和av4一致！！！
                Mp4VideoUtil videoUtil = new Mp4VideoUtil(ffmpegpath, file_av4.getAbsolutePath(),
                        mp4_file_name, file_mp4.getAbsolutePath());
                String s = videoUtil.generateMp4();
                if(!s.equals("success"))
                {
                    log.error("转换失败");
                    mediaFileProcessService.saveProcessFinishStatus(mediaProcess.getId(),"3",fileId,null,"转换失败");
                    return;
                }

                // 上传minio
                String objectName_mp4=objectName.substring(0,objectName.lastIndexOf("."))+".mp4";
                boolean b1 = mediaFileService.addMediaFilesToMinIO(file_mp4.getAbsolutePath(), "video/mp4", mediaProcess.getBucket(), objectName_mp4);
                if(!b1)
                {
                    log.error("转换后，上传失败");
                    mediaFileProcessService.saveProcessFinishStatus(mediaProcess.getId(), "3", fileId, null, "上传mp4到minio失败");
                    return;
                }

                // 更新数据库表
                String url=mediaProcess.getBucket()+objectName_mp4;
                mediaFileProcessService.saveProcessFinishStatus(mediaProcess.getId(),"2",fileId,url,null);


            });
        }


    }


}
