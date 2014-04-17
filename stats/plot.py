import matplotlib as mpl
import matplotlib.pyplot as plt
import numpy as np

def separate_windows(arr):
    ret = dict()
    for k, v in zip(*arr):
        if k in ret:
            ret[k].append(v)
        else:
            ret[k] = [v]
    return ret

def plot_data(filename, output, title,
              xlabel="Window Size", ylabel="Throughput (kbps)"):
    data = np.genfromtxt(
        filename,
        usecols=(0,1,2),
        dtype=np.float, delimiter='\t',
        skip_header=1)
    time_ms, bytes, window_size = data.T
    throughput = bytes*8 / time_ms # kbps
    thr_win = np.array([window_size, throughput])
    window_to_throughput = separate_windows(thr_win)

    N = len(window_to_throughput)
    x_offsets = np.arange(N)

    fig, ax = plt.subplots()

    ax.boxplot(list(window_to_throughput[k]
                    for k in sorted(window_to_throughput)))
    ax.set_title(title)
    ax.set_xlabel(xlabel)
    ax.set_ylabel(ylabel)
    ax.set_xticklabels(list(sorted(window_to_throughput)))

    plt.savefig(output, dpi=500)
