# WISH: User-centric Bitrate Adaptation for HTTP Adaptive Streaming on Mobile Devices #

We implemented our recent proposed ABR algorithm based on a **W**e**I**ghted **S**um model for **H**AS, namely WISH, in [ExoPlayer][].
WISH takes into account three cost factors of each video representation: (a) *throughput cost*, (b) *buffer cost*, and (c) *quality cost*.
An overall cost, which is a weighted sum of these penalties, represents each video representation. Our proposed method provides the optimal segment bitrate by choosing the representation with the lowest overall cost for a certain time. A solution determining the weights of the cost factors based on end users’ preferences and mobile devices will be introduced in this work.

[ExoPlayer]: https://github.com/google/ExoPlayer

## Usage ##

* Download and install this repository in an Android phone.
* To save the experimental results into files, go to **App info** and select **Allow management of all files** in *Files and media permission*.
* Run the experiments.
* Check the experimental results in ```/storage/emulated/0/WISH_ABR_Algorithm/```
The structure of the result folder: ```WISH_ABR_Algorithm/<video_name>/BufMax<buffer_size>/<ABR_algorithm>/<date_time>```

## Authors ##
* Minh Nguyen - Christian Doppler Laboratory ATHENA, Alpen-Adria-Universitaet Klagenfurt - minh.nguyen@aau.at

## Acknowledgments
If you use this source code in your research, please cite 
1. Include the link to this repository
2. Cite the following publication

*Nguyen, M., Cetinkaya, E., Hellwagner, H. and Timmerer, C., "WISH: User-centric Bitrate Adaptation for HTTP Adaptive Streaming on Mobile Devices", In 2021 IEEE 23rd International Workshop on Multi-media Signal Processing (MMSP), IEEE, 2021.*

```
@inproceedings{nguyen2021wish,
  title={{WISH: User-centric Bitrate Adaptation for HTTP Adaptive Streaming on Mobile Devices}},
  author={Nguyen, Minh and Cetinkaya, Anatoliy and Timmerer, Christian and Hellwagner, Hermann},
  booktitle={Proceedings of the IEEE 23rd International Workshop on Multi-media Signal Processing (MMSP)},
  year={2021},
}

```
